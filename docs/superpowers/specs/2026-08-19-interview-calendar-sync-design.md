# DAF360 RH — Interview Outlook/Teams Calendar Sync
## Design Spec — 2026-08-19

---

## 1. Scope

When a candidate interview is created, edited, or cancelled, mirror it as a real Microsoft 365 calendar event with an automatic Teams meeting — organized under the lead interviewer's mailbox, with the rest of the panel and the candidate as attendees — using the exact same Microsoft Graph app registration already live for SharePoint document/photo storage (`Calendars.ReadWrite.All` is already granted on that app, confirmed via its own token's `roles` claim during the SharePoint work — no new Azure AD permission grant needed).

**Explicitly out of scope** (per user confirmation during brainstorming):
- Variable interview duration — every event is a fixed 60 minutes (`scheduledAt` → `scheduledAt + 60min`), matching the existing double-booking check's own assumption. No new duration field.
- In-person-only interviews without a Teams link — every interview gets an online Teams meeting automatically, no toggle.
- Any change to the "Attestation" document-generation SharePoint sync built earlier — unrelated, untouched.

---

## 2. Current State (confirmed via investigation, 2026-08-19)

- `CandidateInterview` (`candidate_interviews` table) has a single `scheduledAt` instant (`OffsetDateTime`) — no end time. `location` is free text. Status is `PLANNED`/`DONE`/`CANCELLED`; result `PASS`/`FAIL` only settable once `DONE`.
- Multiple interviewers are modeled as a real join table, `candidate_interview_interviewers` (`CandidateInterviewInterviewer`), with `CandidateInterview.interviewerUserId` mirroring the first (lead) panel member for back-compat.
- `CandidateInterviewService.create()`/`.update()` are the only two write paths — there is no separate cancel/delete endpoint; cancellation is just `update()` with `status: "CANCELLED"`. Neither method sends any email/notification today — this is the first external-notification touchpoint for interviews.
- Interviewers are plain `Users.id` references (no JPA entity — raw `JdbcTemplate` against `[dbo].[Users]`); real corporate emails exist in `Users.email`/`Users.username` (`COALESCE(email, username)` is the established pattern elsewhere), but today's `listInterviewUsers`/`userNames()` only ever select `id, fullName` — email isn't fetched yet.
- `Candidate.emailPersonal` is `NOT NULL` at both the DB and API-validation level — always available.
- `GraphSharePointService` (`com.daf360.rh.service.sharepoint`) is the architectural template: `@Service @RequiredArgsConstructor`, single `AppProperties` dependency, hand-built `RestClient` (`JdkClientHttpRequestFactory` + redirect-following `HttpClient` — required, Graph's own content endpoints 302-redirect and the default client doesn't follow that), in-memory cached client-credentials token (`volatile` fields, refreshed 60s before expiry), `isConfigured()` gate (blank tenant/client id/secret ⇒ every public method silently no-ops), and a hard rule of never letting a Graph failure propagate to the caller.
- `AppProperties`'s existing `msGraphTenantId`/`msGraphClientId`/`msGraphClientSecret` fields are reused as-is — **no new config needed**, since it's the same app registration.

---

## 3. Architecture

```
CandidateInterviewService.create()/.update()  (after their own DB save succeeds — same
   │                                            "local truth first, external sync is
   │                                            additive best-effort" ordering as SharePoint)
   ▼
syncCalendarEvent(interview, panel, candidate)   /   cancelCalendarEvent(interview)
   │  resolves organizer = lead interviewer's email (panel.get(0))
   │  resolves attendees = rest of panel + candidate.emailPersonal
   │  decides create vs patch vs recreate-under-new-organizer (see §4)
   ▼
GraphCalendarService (new, com.daf360.rh.service.calendar)
   │  createEvent / updateEvent / cancelEvent
   │  same auth/token-caching/isConfigured/never-throw shape as GraphSharePointService
   ▼
Microsoft Graph  POST/PATCH /users/{organizerEmail}/events[/{id}[/cancel]]
   isOnlineMeeting: true, onlineMeetingProvider: "teamsForBusiness"
```

---

## 4. Sync Semantics

**Create** (`CandidateInterviewService.create()`): after the interview + panel are saved, if the panel is non-empty, resolve the lead's email and create the Graph event. Store `graphEventId`, `graphOrganizerEmail`, `graphJoinUrl` on the (still-managed, same-transaction) entity — no second explicit `.save()` needed, JPA dirty-checking flushes it at commit.

**Update** (`CandidateInterviewService.update()`), branching on the interview's status *after* applying the requested changes:
- **`CANCELLED`** → call Graph's cancel-event endpoint (`POST .../events/{id}/cancel` with a comment) rather than a raw delete — this notifies attendees automatically, which a silent delete would not. Clear `graphEventId`/`graphJoinUrl` on success.
- **`DONE`** → no calendar action. The event already happened; nothing to sync.
- **`PLANNED`** (time/location/notes/panel changed, or unchanged but re-saved) →
  - If the **lead interviewer changed** (new `panel.get(0)`'s email ≠ the stored `graphOrganizerEmail`): a Graph event's organizer can't be changed after creation, so cancel the old event under the *old* organizer, then create a fresh one under the *new* organizer. Handled explicitly rather than left as a silently-stale event under the wrong person.
  - Otherwise, if a `graphEventId` already exists: `PATCH` it (time/attendees/location may have changed).
  - Otherwise (no `graphEventId` yet — e.g., Graph was unreachable at creation time): create one now. This is a free, natural self-heal — any subsequent edit retries the sync.

All of this is wrapped in its own try/catch inside `CandidateInterviewService` (in addition to `GraphCalendarService`'s own internal never-throw contract) — matching the double-layered safety net already established for `EmployeeProfileService.mirrorPhotoToSharePoint`, since the *calling* code (email resolution, panel logic) could itself have a bug that must not be allowed to break interview creation/editing.

---

## 5. New Backend Pieces

### 5.1 Migration `V79__candidate_interview_calendar_sync.sql`
Adds three nullable columns to `candidate_interviews`:
- `graph_event_id NVARCHAR(255) NULL` — the Graph event's id, for later PATCH/cancel.
- `graph_organizer_email NVARCHAR(255) NULL` — whose mailbox actually owns the event (may drift from the *current* lead interviewer after an edit — this is exactly why it's tracked separately).
- `graph_join_url NVARCHAR(1000) NULL` — the Teams join link, surfaced directly in the app UI (see §7) so interviewers/HR don't have to dig through their own Outlook.

All three stay `NULL` whenever Graph is unconfigured or a sync attempt fails — never a blocking dependency for the interview record itself.

### 5.2 `GraphCalendarService` (new)
Same shape as `GraphSharePointService`: `createEvent(organizerEmail, subject, start, end, attendeeEmails, location) → Optional<CreatedEvent>` (record of `eventId`/`joinUrl`), `cancelEvent(organizerEmail, eventId, comment)` (void, best-effort, treats 404 as already-gone). Reuses `AppProperties`'s existing Graph fields — no new config.

`updateEvent(...)` returns a 3-state `UpdateOutcome` enum (`SUCCESS` / `EVENT_NOT_FOUND` / `FAILED`) rather than a boolean — this was tightened after an initial boolean version shipped a real bug: the caller's self-heal logic (recreate the event if it's genuinely gone) must fire ONLY on a real 404 (`EVENT_NOT_FOUND`), never on an ambiguous/transient failure (network blip, 429 throttling, 503 timeout, all `FAILED`). A boolean collapsed those two cases together, so a routine throttled PATCH looked identical to "the event was deleted" — the caller would recreate a brand-new event and overwrite `graphEventId`, permanently orphaning the original (still-live) event with no way to ever patch or cancel it again, while the candidate/panel ended up with two invites. `FAILED` means "leave `graphEventId` exactly as-is and do nothing else" — the next edit simply retries the patch.

### 5.3 `CandidateInterviewService` changes
- New `userEmails(Collection<Long> userIds)` helper, mirroring the existing `userNames(...)` pattern, querying `COALESCE(email, username)`.
- New private `syncCalendarEvent(...)` / `cancelCalendarEvent(...)` helpers called from `create()`/`update()` per §4.
- `CandidateInterview` entity gains the three new fields (no `@Builder.Default` — `null` is the correct default, matching how `EmployeeProfile.photoSharepointUrl` was added earlier).
- `CandidateInterviewDto` gains `graphJoinUrl` (read-only, informational) so the frontend can render a join link.

---

## 6. Error Handling Summary

| Situation | Behaviour |
|---|---|
| Graph not configured | Every calendar call no-ops silently; interview create/update always succeeds regardless. |
| Empty panel (no interviewers picked) on *create*, or on an edit that isn't otherwise synced | No organizer to create the event under — sync skipped, logged at debug, not an error. |
| Panel cleared to empty on an edit of an already-`PLANNED` interview that already has a synced `graphEventId` | Treated the same as a cancellation: the existing Graph event is cancelled (organizer/attendees notified) and `graphEventId`/`graphOrganizerEmail`/`graphJoinUrl` are all cleared — there is no longer anyone to organize the event under, so leaving it live would silently orphan it. |
| Interviewer/candidate email missing | Skipped for that attendee only; if the *lead's* email is missing, the whole sync for that interview is skipped (no organizer to create under). |
| Graph API failure (network/auth/permissions) at create/cancel | Logged as a warning, swallowed; interview save is completely unaffected. |
| Graph API failure at update (PATCH) | `EVENT_NOT_FOUND` (real 404 — event genuinely gone) self-heals by creating a fresh event. Any other failure (`FAILED` — network/auth/throttling/timeout, an ambiguous/transient condition) is logged as a warning and left alone: `graphEventId` is NOT touched, no create, no cancel — recreating on an ambiguous failure risks a duplicate event while orphaning a possibly-still-live original. The next edit simply retries the patch. |
| Lead interviewer changed on an edit | Old event cancelled under the old organizer, new one created under the new organizer — never left silently stale. |

---

## 7. Frontend Changes

`daf360-rh-frontend/src/app/modules/candidates/interview.model.ts` — `CandidateInterview` gains `graphJoinUrl: string | null`.

`candidate-interviews.component.ts` template — right next to the existing `location` badge in the interview card (same row, same style), add a "Join Teams meeting" link when `iv.graphJoinUrl` is present. No other UI change — the create/edit form itself needs no new fields, since duration/online-status aren't configurable per this design's scope.

---

## 8. Testing Plan

- Unit tests for `GraphCalendarService`: unconfigured → `Optional.empty()`/no-op for all three methods, guard verified via the same `verify(appProperties, never())...` proof pattern used for `GraphSharePointService`'s own tests.
- Unit tests for `CandidateInterviewService`: create with a panel → calendar service called with the right organizer/attendees, `graphEventId`/`graphOrganizerEmail`/`graphJoinUrl` set on the saved entity; create with an empty panel → calendar service never called; update changing only location/time with the same lead → `updateEvent` called, not `createEvent`; update changing the lead interviewer → old event cancelled, new one created under the new organizer; update to `CANCELLED` → `cancelEvent` called, fields cleared; update to `DONE` → no calendar call at all; a thrown exception from the calendar helper never escapes `create()`/`update()`.
- Manual/live verification (same approach as SharePoint): create a real interview with a real internal interviewer + a real candidate email, confirm the event actually appears in the interviewer's real Outlook calendar with a working Teams join link and the candidate receives the invite; edit the time and confirm the calendar event updates; cancel it and confirm attendees get a cancellation notice; change the lead interviewer and confirm the event moves to the new organizer's calendar and disappears from the old one.
