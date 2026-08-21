# DAF360 RH — Profile Photo SharePoint Integration
## Design Spec — 2026-08-18

---

## 1. Scope

Employee profile photos, currently stored only on local disk, get mirrored to the employee's SharePoint folder on upload, and the local copy becomes a self-healing cache backed by SharePoint rather than the sole copy. The public API surface (`POST`/`GET /api/hr/profiles/{id}/photo`) and every frontend component stay unchanged — this is a backend storage-layer change only.

**Explicitly out of scope** (per user confirmation during brainstorming):
- Bulk backfill of the ~130 employees who already have a locally-stored photo today — only photos uploaded *after* this ships get mirrored to SharePoint. Existing photos keep being served from local disk exactly as today until re-uploaded.
- Any frontend change — `photo_url` contract, `<img>` src pattern, cache-busting (`?v=photoVersion` in facturation's avatar service), the identity-card upload UI: none of it changes.
- Countries other than Tunisia — same graceful no-op as the existing document-generation SharePoint integration; no real folder structure has been confirmed for Egypt/others yet.

---

## 2. Current State (found during investigation, 2026-08-18)

- `EmployeeProfile.photoUrl` (`photo_url` column, owned/created by `daf360-portal`, read/written by `daf360-rh-service`) is a **constant string** — always `/api/hr/profiles/{id}/photo` — never an actual file path. The real file lives on disk at `{storagePath}/profiles/{profileId}/{uuid}.{ext}` (`AppProperties.storagePath`, default `./uploads/hr`).
- Upload: `EmployeeProfileController.uploadPhoto` → `EmployeeProfileService.uploadPhoto` (`EmployeeProfileService.java:477-525`). Validates type (`image/jpeg|png|webp`) and size (max 3 MB), writes a new UUID-named file into the profile's directory. Old files are never deleted — multiple versions can accumulate on disk over time (pre-existing behavior, not in scope to fix here).
- Serve: `EmployeeProfileController.servePhoto` → `EmployeeProfileService.servePhoto` (`EmployeeProfileService.java:605-630`). Picks the **most-recently-modified file** in the profile's directory and streams it, sniffing content-type from magic bytes. No file found → 404, cached 1 hour. Found → 7-day `Cache-Control`. **No `@PreAuthorize`** — deliberately public, since `<img>` tags can't carry a JWT (`SecurityConfig.java:51`).
- The existing SharePoint client, `GraphSharePointService` (`com.daf360.rh.service.sharepoint`), currently exposes exactly **one** public method: `uploadDocument(locationTemplate, employeeFolderName, fileName, content)`. No download/list/delete capability exists — nothing has ever needed to read a file back from SharePoint before this feature.
- Employee → folder resolution is inline in `PdfDocumentService.saveGeneratedDocument` (not a shared/reusable resolver): folder name = `Users.fullName`, trimmed and whitespace-collapsed; `hasAmbiguousEmployeeFolder(fullName, paysId)` skips the SharePoint step (local save still happens) if two employees in the same `pays` share the exact same full name.
- The document-generation precedent's failure philosophy: **local disk is the source of truth, SharePoint is a best-effort mirror that never throws and never blocks the primary save.** This spec follows the same philosophy for the upload side, but flips which copy is "authoritative" for the read side (see §4).

---

## 3. Architecture

```
Upload:  POST /api/hr/profiles/{id}/photo  (unchanged endpoint/contract)
   │
   ▼
EmployeeProfileService.uploadPhoto
   │  1. validate type/size (unchanged)
   │  2. save to local disk (unchanged, always happens, unconditional)
   │  3. resolve employee folder (Users.fullName + ambiguity guard — same pattern as documents)
   │  4. resolve pays.photo_sharepoint_location (new, nullable, Tunisia-only for now)
   │  5. best-effort: GraphSharePointService.uploadDocument(...) with a FIXED filename
   │       → store resulting webUrl in employee_profiles.photo_sharepoint_url (new, nullable)
   ▼
(never throws past step 2 — local save always wins)

Serve:   GET /api/hr/profiles/{id}/photo  (unchanged endpoint/contract)
   │
   ▼
EmployeeProfileService.servePhoto
   │  1. look for a local file (unchanged fast path)
   │  2. NEW: if none found AND SharePoint is configured for this employee's pays,
   │          GraphSharePointService.downloadFile(path) — new method
   │          → write into local cache dir → serve it
   │  3. if still nothing (SharePoint unconfigured/unreachable/no file there either) → 404 (unchanged)
```

---

## 4. Backend Changes (`daf360-rh-service`)

### 4.1 Migration — new file, next available `V**` number
- `pays.photo_sharepoint_location NVARCHAR(500) NULL` — same `{employeeFolder}` placeholder convention as `document_templates.sharepoint_location`. Seeded **only** for Tunisia:
  `Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents`
  *(This exact subfolder name — "Identity Documents" — is provisional. Your memory notes the real identity-documents subfolder for Tunisia hasn't been confirmed against the live SharePoint tree yet. The folder auto-creates on first upload if missing, same mkdir-p behavior as documents, so nothing breaks if the name turns out to be wrong — but flag the real name to me before/soon after this ships and it's a one-line fix.)*
  All other pays: `NULL` → SharePoint step no-ops for them, same as document generation today.
- `employee_profiles.photo_sharepoint_url NVARCHAR(1000) NULL` — traceability column, mirrors `generated_documents.sharepoint_url`. Not used to reconstruct the download path (that's re-derived deterministically, same as upload); purely for visibility/debugging.

### 4.2 `GraphSharePointService` — one new public method
```java
public Optional<byte[]> downloadFile(String path) {
    if (!isConfigured()) return Optional.empty();
    try {
        String token = getAccessToken();
        String siteId = getSiteId(token);
        byte[] content = restClient.get()
                .uri("https://graph.microsoft.com/v1.0/sites/{siteId}/drive/root:/{path}:/content", siteId, path)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(byte[].class);
        return Optional.ofNullable(content);
    } catch (Exception e) {
        log.warn("GraphSharePointService: could not download {} — {}", path, e.getMessage());
        return Optional.empty();
    }
}
```
- Same defensive style as `uploadDocument` — never throws, `Optional.empty()` on any failure (unconfigured, 404, auth, network).
- Raw `path` passed straight into `RestClient`'s URI template (no manual `URLEncoder` pre-encoding) — repeating the double-encoding bug found and fixed during the original SharePoint work (`Abdellatif%2520SASSI`) is the one thing to actively avoid here.
- Class javadoc updated to reflect the service now has two real consumers (documents: upload-only; photos: upload + download), no longer "upload only, by design."

### 4.3 `EmployeeProfileService.uploadPhoto` — extended, not replaced
After the existing local save (unchanged, still first and unconditional):
- Resolve `Users.fullName` for this profile's user + `hasAmbiguousEmployeeFolder(fullName, paysId)` (reuse the exact same check `PdfDocumentService` already has — worth extracting to a small shared helper rather than duplicating the query, since it'll now be called from two services).
- Resolve `pays.photo_sharepoint_location` for this employee's `pays_id`. If null/blank, or the folder is ambiguous: stop here (local save already succeeded, nothing else to do).
- Determine a fixed SharePoint filename from the validated content-type: `Photo.jpg` / `Photo.png` / `Photo.webp`. Using a **fixed name** (not a UUID) is deliberate: Graph's simple PUT upload is an upsert-by-path, so a re-upload cleanly overwrites the same SharePoint file instead of accumulating versions the way local disk currently does.
- Call `graphSharePointService.uploadDocument(photoLocationTemplate, employeeFolder, fixedFileName, bytes)`; on success, persist the returned URL into `employee_profiles.photo_sharepoint_url`. On empty result, log at debug level and move on — the local save already succeeded, this is best-effort only.

### 4.4 `EmployeeProfileService.servePhoto` — extended, not replaced
- Unchanged fast path: local file found → serve it exactly as today.
- **New** fallback, only on a genuine miss (no local file at all): if `photo_sharepoint_location` resolves for this employee's `pays`, and the employee folder is resolvable/unambiguous, re-derive the same deterministic path used at upload time (`{template with employeeFolder}/{fixedFileName}` — try `.jpg`, `.png`, `.webp` in turn, since the extension used at upload time isn't separately persisted) and call `graphSharePointService.downloadFile(path)`. On success: write the bytes into the local cache directory (so subsequent requests hit the fast path again) and serve them. On failure/empty: fall through to today's 404 (cached 1 hour), unchanged.
- This adds latency **only** on a cache-miss — the common case (local file present) is untouched.

---

## 5. Frontend Changes

None. `photo_url` stays a constant API path, the `<img>` src pattern (`avatar.utils.ts`), the upload FAB (`identity-card.component.ts`, `onboarding-form.component.ts`), and the cross-module avatar/cache-busting logic in facturation's `employee-avatar.service.ts` are all unaffected — they only ever talk to the same two endpoints, whose contracts don't change.

---

## 6. Error Handling Summary

| Failure | Behaviour |
|---|---|
| SharePoint not configured (blank Graph credentials) | Upload: local save succeeds, SharePoint step no-ops silently. Serve: cache-miss falls straight through to 404, unchanged from today. |
| Employee's `pays` has no `photo_sharepoint_location` (everyone except Tunisia, for now) | Same as above — no-op, not an error. |
| Employee full name is ambiguous (name collision within the same pays) | SharePoint step skipped, logged; local save unaffected. |
| SharePoint upload fails (network, auth, permissions, 5xx) | Local save already succeeded; failure logged only, never surfaced to the caller. |
| SharePoint download fails on a cache-miss serve | Falls through to today's 404 behavior; never a hard error. |
| Employee re-uploads a photo | Local: new UUID file added (existing behavior, unchanged). SharePoint: same fixed filename is overwritten in place — no version pile-up. |

Never throws past `EmployeeProfileService` — both the upload and serve endpoints must keep working exactly as they do today even if SharePoint is completely unreachable.

---

## 7. Testing Plan

- Unit test `GraphSharePointService.downloadFile()`: mocked `RestClient` — happy path, unconfigured → empty, 404/exception → empty, no propagation.
- Unit test `EmployeeProfileService.uploadPhoto`: local save always happens regardless of SharePoint outcome; fixed filename derived correctly per content-type; ambiguous-name case skips SharePoint without affecting the local save.
- Unit test `EmployeeProfileService.servePhoto`: local-file-present path unchanged (no SharePoint call at all — verify this, to confirm no added latency on the common path); cache-miss path calls `downloadFile` and re-populates the local cache; cache-miss + SharePoint failure still returns 404.
- Manual/live verification (same approach as the original SharePoint document work): pick one real test employee, upload a photo, confirm it lands in their real SharePoint folder under the resolved subfolder name; delete the local cached file only, re-request the photo, confirm it self-heals from SharePoint and gets re-cached.
- Confirm the provisional "Identity Documents" folder name against the real SharePoint tree before/soon after shipping; update the `V**` seed value if it turns out to be named differently.
