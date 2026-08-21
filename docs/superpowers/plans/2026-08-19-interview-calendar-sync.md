# Interview Outlook/Teams Calendar Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> **STANDING RULE — DO NOT COMMIT OR PUSH.** Active standing instruction: do not commit or push anything in this repo without explicit go-ahead. Every task ends with a "mark task complete" step instead of a `git commit` step.

**Goal:** Sync candidate interviews (create/edit/cancel) to a real Microsoft 365 calendar event with an automatic Teams meeting, organized under the lead interviewer's mailbox, using the same Graph app registration already live for SharePoint.

**Architecture:** New `GraphCalendarService` mirrors `GraphSharePointService`'s exact shape (auth, token caching, `isConfigured()` gate, never-throw contract) — no new Azure AD permission or config needed. `CandidateInterviewService.create()`/`.update()` call it synchronously, best-effort, after their own DB save succeeds.

**Tech Stack:** Spring Boot, JPA, SQL Server, `RestClient`, JUnit 5 + Mockito, Angular signals.

**Full design spec:** `docs/superpowers/specs/2026-08-19-interview-calendar-sync-design.md`

---

## Task 1: Migration — calendar sync columns

**Files:**
- Create: `src/main/resources/db/seed/V79__candidate_interview_calendar_sync.sql`

- [x] **Step 1: Write the migration**

```sql
-- =============================================================================
-- V79__candidate_interview_calendar_sync.sql
-- Ajoute le suivi de synchronisation Outlook/Teams pour les entretiens candidat
-- (cf. docs/superpowers/specs/2026-08-19-interview-calendar-sync-design.md).
--
-- Trois colonnes nullable, additives — un entretien reste utilisable normalement
-- même si la synchronisation calendrier échoue ou n'est pas configurée :
--   - graph_event_id       : id de l'événement Graph, pour PATCH/annulation ultérieurs
--   - graph_organizer_email: sous quelle boîte mail l'événement existe réellement —
--                            peut diverger de l'intervieweur principal ACTUEL après
--                            une modification (c'est justement pourquoi on la trace
--                            séparément), un événement Graph ne peut pas changer
--                            d'organisateur après création
--   - graph_join_url       : lien Teams, affiché directement dans l'app
--
-- Idempotente : ADD gardé par IF NOT EXISTS.
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_event_id'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_event_id] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_organizer_email'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_organizer_email] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_join_url'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_join_url] NVARCHAR(1000) NULL;
GO
```

- [x] **Step 2: Apply it to the local `DAF360_HR` database and verify**

```sql
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'candidate_interviews'
  AND COLUMN_NAME IN ('graph_event_id', 'graph_organizer_email', 'graph_join_url');
```
Expected: all 3 columns listed.

- [x] **Step 3: Mark task complete** (do NOT commit)

---

## Task 2: `GraphCalendarService`

**Files:**
- Create: `src/main/java/com/daf360/rh/service/calendar/GraphCalendarService.java`
- Test: `src/test/java/com/daf360/rh/calendar/GraphCalendarServiceTest.java`

Reuses the EXISTING `AppProperties.msGraphTenantId/ClientId/ClientSecret` fields as-is — no new config needed (same app registration, `Calendars.ReadWrite.All` already confirmed granted). Mirrors `GraphSharePointService`'s auth/token-caching/never-throw shape exactly — read that file first (`src/main/java/com/daf360/rh/service/sharepoint/GraphSharePointService.java`) to match its conventions precisely, since this is a sibling, not a rewrite of the pattern.

- [x] **Step 1: Write the failing tests**

```java
package com.daf360.rh.calendar;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.service.calendar.GraphCalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GraphCalendarServiceTest {

    @Mock AppProperties appProperties;
    @InjectMocks GraphCalendarService service;

    // Same precedent as GraphSharePointServiceTest: appProperties is an unstubbed
    // mock, so isConfigured() is false without needing to stub anything, and each
    // method must return before ever touching RestClient/the network. never() on
    // getMsGraphClientId() proves the guard actually short-circuited — an empty/void
    // result alone doesn't rule out a broken guard, since a real (failing) network
    // call further downstream would also be swallowed by each method's own
    // catch(Exception) and produce the same observable result.

    @Test
    void createEvent_returnsEmptyWhenNotConfigured() {
        assertThat(service.createEvent("lead@arx.ing", "Entretien - Alice M",
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                List.of("candidate@example.com"), "Salle A"))
                .isEmpty();

        verify(appProperties, never()).getMsGraphClientId();
    }

    @Test
    void updateEvent_doesNothingWhenNotConfigured() {
        service.updateEvent("lead@arx.ing", "evt-123", "Entretien - Alice M",
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                List.of("candidate@example.com"), "Salle A");

        verify(appProperties, never()).getMsGraphClientId();
    }

    @Test
    void cancelEvent_doesNothingWhenNotConfigured() {
        service.cancelEvent("lead@arx.ing", "evt-123", "Entretien annulé");

        verify(appProperties, never()).getMsGraphClientId();
    }
}
```

- [x] **Step 2: Run them to verify they fail**

Run: `mvn -o test -Dtest=GraphCalendarServiceTest`
Expected: FAIL to compile — `GraphCalendarService` does not exist.

- [x] **Step 3: Implement `GraphCalendarService`**

```java
package com.daf360.rh.service.calendar;

import com.daf360.rh.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client Microsoft Graph minimal pour les événements calendrier — auth (client
 * credentials, même app registration que GraphSharePointService, permission
 * Calendars.ReadWrite.All déjà accordée) + création/mise à jour/annulation d'un
 * événement avec réunion Teams automatique. Portée volontairement étroite : un
 * seul consommateur (CandidateInterviewService), pas de gestion de salle de
 * réunion physique, pas de récurrence.
 *
 * Conçu pour ne JAMAIS faire échouer l'appelant : toute erreur (config absente,
 * auth invalide, réseau, permissions, événement introuvable) est loguée et
 * avalée — l'appelant reçoit Optional.empty() (création) ou rien du tout (mise à
 * jour/annulation, void) et l'entretien reste enregistré tel quel côté DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphCalendarService {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final DateTimeFormatter GRAPH_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                    java.net.http.HttpClient.newBuilder()
                            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                            .build()))
            .build();

    private volatile String  cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public record CreatedEvent(String eventId, String joinUrl) {}

    /** Crée l'événement sous la boîte mail de organizerEmail, avec réunion Teams
     * automatique. Ne lance jamais d'exception — Optional.empty() si non configuré
     * ou en cas d'échec. */
    public Optional<CreatedEvent> createEvent(String organizerEmail, String subject,
            OffsetDateTime start, OffsetDateTime end, List<String> attendeeEmails, String location) {
        if (!isConfigured()) {
            log.debug("Graph non configuré (tenant/client id vide) — création d'événement ignorée pour {}", subject);
            return Optional.empty();
        }
        try {
            String token = getAccessToken();
            EventResponse resp = restClient.post()
                    .uri(GRAPH_BASE + "/users/" + organizerEmail + "/events")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildEventBody(subject, start, end, attendeeEmails, location))
                    .retrieve()
                    .body(EventResponse.class);
            if (resp == null || resp.id() == null) return Optional.empty();
            String joinUrl = resp.onlineMeeting() != null ? resp.onlineMeeting().joinUrl() : null;
            log.info("Événement calendrier créé pour {} sous {}: {}", subject, organizerEmail, resp.id());
            return Optional.of(new CreatedEvent(resp.id(), joinUrl));
        } catch (Exception e) {
            log.warn("Échec de création de l'événement calendrier pour {} sous {}: {}",
                    subject, organizerEmail, e.getMessage());
            return Optional.empty();
        }
    }

    /** Met à jour un événement existant (horaire/participants/lieu). Best-effort,
     * ne lance jamais d'exception. */
    public void updateEvent(String organizerEmail, String eventId, String subject,
            OffsetDateTime start, OffsetDateTime end, List<String> attendeeEmails, String location) {
        if (!isConfigured()) {
            log.debug("Graph non configuré — mise à jour d'événement ignorée pour {}", eventId);
            return;
        }
        try {
            String token = getAccessToken();
            restClient.patch()
                    .uri(GRAPH_BASE + "/users/" + organizerEmail + "/events/" + eventId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildEventBody(subject, start, end, attendeeEmails, location))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound notFound) {
            log.warn("Événement calendrier {} introuvable sous {} — mise à jour ignorée", eventId, organizerEmail);
        } catch (Exception e) {
            log.warn("Échec de mise à jour de l'événement calendrier {}: {}", eventId, e.getMessage());
        }
    }

    /** Annule un événement existant (notifie les participants) — préféré à une
     * suppression silencieuse. Un événement déjà absent (404) est traité comme un
     * succès. Best-effort, ne lance jamais d'exception. */
    public void cancelEvent(String organizerEmail, String eventId, String comment) {
        if (!isConfigured()) {
            log.debug("Graph non configuré — annulation d'événement ignorée pour {}", eventId);
            return;
        }
        try {
            String token = getAccessToken();
            restClient.post()
                    .uri(GRAPH_BASE + "/users/" + organizerEmail + "/events/" + eventId + "/cancel")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("comment", comment))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound notFound) {
            // déjà absent — rien à faire
        } catch (Exception e) {
            log.warn("Échec de l'annulation de l'événement calendrier {}: {}", eventId, e.getMessage());
        }
    }

    private Map<String, Object> buildEventBody(String subject, OffsetDateTime start, OffsetDateTime end,
            List<String> attendeeEmails, String location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("start", Map.of(
                "dateTime", start.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(GRAPH_DATETIME),
                "timeZone", "UTC"));
        body.put("end", Map.of(
                "dateTime", end.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(GRAPH_DATETIME),
                "timeZone", "UTC"));
        body.put("attendees", attendeeEmails.stream()
                .map(email -> Map.of(
                        "emailAddress", Map.of("address", email),
                        "type", "required"))
                .toList());
        body.put("isOnlineMeeting", true);
        body.put("onlineMeetingProvider", "teamsForBusiness");
        if (location != null && !location.isBlank()) {
            body.put("location", Map.of("displayName", location));
        }
        return body;
    }

    private boolean isConfigured() {
        return notBlank(appProperties.getMsGraphTenantId())
                && notBlank(appProperties.getMsGraphClientId())
                && notBlank(appProperties.getMsGraphClientSecret());
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Identique à GraphSharePointService.getAccessToken() — même app registration,
     * même cache en mémoire renouvelé 60s avant expiration réelle. */
    private synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", appProperties.getMsGraphClientId());
        form.add("client_secret", appProperties.getMsGraphClientSecret());
        form.add("scope", "https://graph.microsoft.com/.default");

        String url = "https://login.microsoftonline.com/" + appProperties.getMsGraphTenantId()
                + "/oauth2/v2.0/token";
        TokenResponse resp = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (resp == null || resp.accessToken() == null) {
            throw new IllegalStateException("Réponse de jeton vide depuis Microsoft identity platform");
        }
        cachedToken = resp.accessToken();
        int expiresIn = resp.expiresIn() != null ? resp.expiresIn() : 3600;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        return cachedToken;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Integer expiresIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EventResponse(String id, OnlineMeetingInfo onlineMeeting) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OnlineMeetingInfo(String joinUrl) {}
}
```

Note: this class deliberately does NOT share a token cache instance with `GraphSharePointService` (each has its own `cachedToken`/`tokenExpiresAt` fields) even though they authenticate against the same app registration — matches the plan's "no shared facade" precedent (each Graph-consuming service in this codebase owns its own client/cache, per `GraphSharePointService`'s own existing shape). This means two independent token fetches the first time each is used, but each caches thereafter — a deliberate, acceptable simplicity trade-off, not an oversight.

- [x] **Step 4: Run the tests to verify they pass**

Run: `mvn -o test -Dtest=GraphCalendarServiceTest`
Expected: PASS, 3/3 green.

- [x] **Step 5: Mark task complete** (do NOT commit)

---

## Task 3: `CandidateInterviewService` — sync on create/update

**Files:**
- Modify: `src/main/java/com/daf360/rh/domain/CandidateInterview.java`
- Modify: `src/main/java/com/daf360/rh/dto/interview/CandidateInterviewDto.java`
- Modify: `src/main/java/com/daf360/rh/service/CandidateInterviewService.java`
- Modify: `src/test/java/com/daf360/rh/interview/CandidateInterviewServiceTest.java`

- [x] **Step 1: Add the new entity fields**

In `CandidateInterview.java`, right after `sequenceNumber`:

```java
    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "graph_event_id", length = 255)
    private String graphEventId;

    @Column(name = "graph_organizer_email", length = 255)
    private String graphOrganizerEmail;

    @Column(name = "graph_join_url", length = 1000)
    private String graphJoinUrl;
```

- [x] **Step 2: Add `graphJoinUrl` to the response DTO**

In `CandidateInterviewDto.java`:

```java
public record CandidateInterviewDto(
        Long id,
        Long candidateId,
        Long interviewTypeId,
        String interviewTypeName,
        OffsetDateTime scheduledAt,
        String location,
        String interviewerNotes,
        Long interviewerUserId,
        String interviewerName,
        List<UserPickerDto> interviewers,
        String status,
        String result,
        Integer sequenceNumber,
        OffsetDateTime createdAt,
        String graphJoinUrl
) {}
```

- [x] **Step 3: Write the failing tests**

Read the live `CandidateInterviewServiceTest.java` first — it already has 13 passing tests using `@InjectMocks`. Add one new mock field (this alone requires no changes to any existing test — `@Mock` fields with no stubs configured on them in a given test are never flagged by Mockito's strict stubbing, only unused `when(...)` stubs are):

```java
    @Mock CandidateInterviewRepository interviewRepo;
    @Mock CandidateInterviewInterviewerRepository panelRepo;
    @Mock InterviewTypeRepository      typeRepo;
    @Mock CandidateRepository          candidateRepo;
    @Mock TenantService                tenantService;
    @Mock JdbcTemplate                 jdbcTemplate;
    @Mock com.daf360.rh.service.calendar.GraphCalendarService graphCalendarService;

    @InjectMocks CandidateInterviewService service;
```

Add these new tests at the end of the class (before the closing `}`):

```java
    // ── calendar sync ─────────────────────────────────────────────────────────

    @Test
    void create_withPanel_createsCalendarEventUnderLeadOrganizer() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        // First jdbcTemplate.query call (userNames, for the DTO) and the second
        // (userEmails, for the calendar sync) share the same generic signature —
        // distinguish them by matching on the actual SQL text.
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenAnswer(inv -> List.of()); // overridden per-test below where a real email matters

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), "Salle A", null, List.of(4L, 5L));
        service.create(CANDIDATE_ID, req, ACTOR_ID);

        // No real interviewer email resolved above (empty list) — so no organizer
        // to create the event under, and the calendar service must never be called.
        verify(graphCalendarService, never()).createEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_withResolvedLeadEmail_callsCreateEventWithAllAttendees() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    // Simulate two rows: lead (4L) and panel member (5L)
                    java.sql.ResultSet rsLead = mock(java.sql.ResultSet.class);
                    when(rsLead.getLong("id")).thenReturn(4L);
                    when(rsLead.getString("email")).thenReturn("lead@arx.ing");
                    java.sql.ResultSet rsOther = mock(java.sql.ResultSet.class);
                    when(rsOther.getLong("id")).thenReturn(5L);
                    when(rsOther.getString("email")).thenReturn("other@arx.ing");
                    return List.of(mapper.mapRow(rsLead, 0), mapper.mapRow(rsOther, 1));
                });
        when(graphCalendarService.createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-1", "https://teams.microsoft.com/l/meetup-join/evt-1")));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), "Salle A", null, List.of(4L, 5L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-1", result.graphJoinUrl());
        verify(graphCalendarService).createEvent(
                eq("lead@arx.ing"), any(),
                eq(req.scheduledAt()), eq(req.scheduledAt().plusMinutes(60)),
                argThat(attendees -> ((List<?>) attendees).containsAll(
                        List.of("other@arx.ing", "alice@example.com"))),
                eq("Salle A"));
    }

    @Test
    void create_emptyPanel_neverCallsCalendarService() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, null);
        service.create(CANDIDATE_ID, req, ACTOR_ID);

        verifyNoInteractions(graphCalendarService);
    }

    @Test
    void update_toCancelled_callsCancelEventAndClearsFields() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        interview.setGraphJoinUrl("https://teams.microsoft.com/l/meetup-join/evt-1");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, null, "CANCELLED", null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).cancelEvent(eq("lead@arx.ing"), eq("evt-1"), any());
        assertNull(result.graphJoinUrl());
    }

    @Test
    void update_toDone_neverTouchesCalendarService() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, null, "DONE", "PASS");
        service.update(100L, req, ACTOR_ID);

        verifyNoInteractions(graphCalendarService);
    }

    @Test
    void update_leadInterviewerChanges_cancelsOldEventAndCreatesNewOne() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-old");
        interview.setGraphOrganizerEmail("old-lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(9L);
                    when(rs.getString("email")).thenReturn("new-lead@arx.ing");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(graphCalendarService.createEvent(eq("new-lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-new", "https://teams.microsoft.com/l/meetup-join/evt-new")));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, List.of(9L), null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).cancelEvent(eq("old-lead@arx.ing"), eq("evt-old"), any());
        verify(graphCalendarService).createEvent(eq("new-lead@arx.ing"), any(), any(), any(), any(), any());
        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-new", result.graphJoinUrl());
    }

    @Test
    void update_sameOrganizerExistingEvent_patchesInPlaceWithoutCreatingAnother() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(4L);
                    when(rs.getString("email")).thenReturn("lead@arx.ing");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(graphCalendarService.updateEvent(eq("lead@arx.ing"), eq("evt-1"), any(), any(), any(), any(), any()))
                .thenReturn(true);

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(5), "Salle B", null, null, null, null);
        service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).updateEvent(eq("lead@arx.ing"), eq("evt-1"), any(), any(), any(), any(), eq("Salle B"));
        verify(graphCalendarService, never()).createEvent(any(), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).cancelEvent(any(), any(), any());
    }

    @Test
    void update_patchFails_fallsBackToCreatingFreshEvent() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-stale");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(4L);
                    when(rs.getString("email")).thenReturn("lead@arx.ing");
                    return List.of(mapper.mapRow(rs, 0));
                });
        // The stored event was deleted out from under us (e.g. manually in Outlook) —
        // updateEvent reports failure, so the self-heal path must kick in.
        when(graphCalendarService.updateEvent(eq("lead@arx.ing"), eq("evt-stale"), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(graphCalendarService.createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-fresh", "https://teams.microsoft.com/l/meetup-join/evt-fresh")));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(5), null, null, null, null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        // Self-healed: a fresh event was created under the SAME organizer (no
        // organizer change here, just a dead event id) — and NOT preceded by a
        // cancelEvent call, since there's nothing valid left to cancel.
        verify(graphCalendarService).createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).cancelEvent(any(), any(), any());
        assertEquals("evt-fresh", interview.getGraphEventId());
        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-fresh", result.graphJoinUrl());
    }

    @Test
    void syncCalendarEvent_neverThrows_whenCalendarServiceBlowsUp() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any()))
                .thenThrow(new RuntimeException("DB connection reset"));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, List.of(4L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertNotNull(result); // create() itself must succeed regardless
        verify(interviewRepo).save(any());
    }
```

Add the missing static imports this needs, if not already present: `import static org.mockito.ArgumentMatchers.contains;`, `import static org.mockito.ArgumentMatchers.argThat;`, `import static org.mockito.Mockito.mock;`, `import static org.mockito.Mockito.verifyNoInteractions;` (check the existing `import static org.mockito.Mockito.*;` and `import static org.mockito.ArgumentMatchers.*;` — if the file already uses wildcard imports for these, nothing to add).

- [x] **Step 4: Run them to verify they fail**

Run: `mvn -o test -Dtest=CandidateInterviewServiceTest`
Expected: FAIL to compile (`GraphCalendarService` not yet injected, `graphJoinUrl` not yet on the DTO/entity, `syncCalendarEvent`/`cancelCalendarEvent` not yet called anywhere).

- [x] **Step 5: Implement in `CandidateInterviewService.java`**

Read the live file first. Add the import:

```java
import com.daf360.rh.service.calendar.GraphCalendarService;
```

Add a new field at the end of the existing field list:

```java
    private final JdbcTemplate                 jdbcTemplate;
    private final GraphCalendarService          graphCalendarService;
```

In `create(...)`, right after `if (!panel.isEmpty()) savePanel(saved.getId(), panel);` and before `return toDto(...)`, add:

```java
        if (!panel.isEmpty()) {
            syncCalendarEvent(saved, panel, candidate);
        }
        return toDto(saved, type.getName(), panel, userNames(panel));
```

(replacing the existing `if (!panel.isEmpty()) savePanel(saved.getId(), panel);` line with both statements — `savePanel` stays exactly as it was, `syncCalendarEvent` is new, both gated the same way since there's no organizer without a panel).

In `update(...)`, right after `if (req.interviewerUserIds() != null) savePanel(saved.getId(), panel);` and before building `typeName`/`return toDto(...)`, add:

```java
        if (req.interviewerUserIds() != null) savePanel(saved.getId(), panel);

        if (saved.getStatus() == InterviewStatus.CANCELLED) {
            cancelCalendarEvent(saved);
        } else if (saved.getStatus() == InterviewStatus.PLANNED && !panel.isEmpty()) {
            syncCalendarEvent(saved, panel, candidate);
        }

        String typeName = typeRepo.findById(saved.getInterviewTypeId())
                .map(InterviewType::getName).orElse(null);
        return toDto(saved, typeName, panel, userNames(panel));
```

Update `toDto(...)` to pass the new field through:

```java
    private CandidateInterviewDto toDto(CandidateInterview ci, String typeName,
                                        List<Long> panel, Map<Long, String> names) {
        List<UserPickerDto> interviewers = panel.stream()
                .map(uid -> new UserPickerDto(uid, names.get(uid)))
                .toList();
        String leadName = ci.getInterviewerUserId() != null
                ? names.get(ci.getInterviewerUserId())
                : null;
        return new CandidateInterviewDto(
                ci.getId(),
                ci.getCandidateId(),
                ci.getInterviewTypeId(),
                typeName,
                ci.getScheduledAt(),
                ci.getLocation(),
                ci.getInterviewerNotes(),
                ci.getInterviewerUserId(),
                leadName,
                interviewers,
                ci.getStatus().name(),
                ci.getResult() != null ? ci.getResult().name() : null,
                ci.getSequenceNumber(),
                ci.getCreatedAt(),
                ci.getGraphJoinUrl());
    }
```

Add these new private helpers (near `userNames(...)`):

```java
    // ── calendar sync (Outlook/Teams) ────────────────────────────────────────

    private static final int CALENDAR_EVENT_DURATION_MINUTES = 60;

    /** userId → email (COALESCE(email, username), same convention used elsewhere in
     * this codebase) for a batch of users, in one query — sibling of userNames(). */
    private Map<Long, String> userEmails(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<UserEmail> rows = jdbcTemplate.query(
                "SELECT id, COALESCE(email, username) AS email FROM Users WHERE id IN (" + placeholders + ")",
                (rs, rn) -> new UserEmail(rs.getLong("id"), rs.getString("email")),
                userIds.toArray());
        Map<Long, String> emails = new LinkedHashMap<>();
        rows.forEach(u -> emails.put(u.id(), u.email()));
        return emails;
    }

    private record UserEmail(Long id, String email) {}

    /** Best-effort: creates, patches, or (if the lead interviewer changed) recreates
     * the Graph calendar event for this interview. Never throws — a bug here must
     * never break interview creation/editing, matching mirrorPhotoToSharePoint's
     * same double-layered safety net (GraphCalendarService itself also never
     * throws, but the surrounding email-resolution logic here could). */
    private void syncCalendarEvent(CandidateInterview interview, List<Long> panel, Candidate candidate) {
        try {
            Long leadUserId = panel.get(0);
            Map<Long, String> emails = userEmails(panel);
            String organizerEmail = emails.get(leadUserId);
            if (organizerEmail == null) return;

            List<String> attendeeEmails = new ArrayList<>();
            for (int i = 1; i < panel.size(); i++) {
                String e = emails.get(panel.get(i));
                if (e != null) attendeeEmails.add(e);
            }
            if (candidate.getEmailPersonal() != null) attendeeEmails.add(candidate.getEmailPersonal());

            String subject = "Entretien - " + candidate.getFirstName() + " " + candidate.getLastName();
            OffsetDateTime start = interview.getScheduledAt();
            OffsetDateTime end = start.plusMinutes(CALENDAR_EVENT_DURATION_MINUTES);

            boolean organizerChanged = interview.getGraphEventId() != null
                    && interview.getGraphOrganizerEmail() != null
                    && !interview.getGraphOrganizerEmail().equals(organizerEmail);

            boolean needsCreate = interview.getGraphEventId() == null || organizerChanged;

            if (!needsCreate) {
                // Same organizer, event already exists — try to patch it in place.
                // updateEvent() returns false if the event was deleted out from under
                // us (404) or the call otherwise failed — in that case, fall through
                // to create a fresh one below rather than leaving graphEventId
                // pointing at a permanently-dead event with no recovery path.
                boolean updated = graphCalendarService.updateEvent(organizerEmail, interview.getGraphEventId(),
                        subject, start, end, attendeeEmails, interview.getLocation());
                needsCreate = !updated;
            }

            if (needsCreate) {
                if (organizerChanged) {
                    graphCalendarService.cancelEvent(interview.getGraphOrganizerEmail(), interview.getGraphEventId(),
                            "Cet entretien a été réorganisé avec un nouvel intervieweur principal.");
                }
                graphCalendarService.createEvent(organizerEmail, subject, start, end, attendeeEmails, interview.getLocation())
                        .ifPresentOrElse(created -> {
                            interview.setGraphEventId(created.eventId());
                            interview.setGraphOrganizerEmail(organizerEmail);
                            interview.setGraphJoinUrl(created.joinUrl());
                        }, () -> { /* échec ou non configuré — les champs restent tels quels */ });
            }
        } catch (Exception e) {
            log.warn("Échec de la synchronisation calendrier pour l'entretien {}: {}",
                    interview.getId(), e.getMessage());
        }
    }

    /** Best-effort: cancels the Graph calendar event tied to this interview, if any. */
    private void cancelCalendarEvent(CandidateInterview interview) {
        try {
            if (interview.getGraphEventId() == null || interview.getGraphOrganizerEmail() == null) return;
            graphCalendarService.cancelEvent(interview.getGraphOrganizerEmail(), interview.getGraphEventId(),
                    "Cet entretien a été annulé.");
            interview.setGraphEventId(null);
            interview.setGraphJoinUrl(null);
        } catch (Exception e) {
            log.warn("Échec de l'annulation calendrier pour l'entretien {}: {}",
                    interview.getId(), e.getMessage());
        }
    }
```

Add the missing import if not already present: `import java.util.LinkedHashMap;` (check first — `LinkedHashMap` is likely already imported for `userNames`/other helpers; if so, skip).

- [x] **Step 6: Run the tests to verify they pass**

Run: `mvn -o test -Dtest=CandidateInterviewServiceTest`
Expected: PASS, all tests in the class green (13 existing + 9 new = 22).

- [x] **Step 7: Run the full suite**

Run: `mvn -o clean test`
Expected: same pre-existing baseline as established in earlier sessions today (unrelated failures in a handful of other test classes), no *new* regressions.

- [x] **Step 8: Mark task complete** (do NOT commit)

---

## Task 4: Frontend — surface the Teams join link

**Files:**
- Modify: `src/app/modules/candidates/interview.model.ts`
- Modify: `src/app/modules/candidates/candidate-interviews.component.ts`

- [x] **Step 1: Add the field to the model**

In `interview.model.ts`, in the `CandidateInterview` interface:

```typescript
export interface CandidateInterview {
  id: number;
  candidateId: number;
  interviewTypeId: number;
  interviewTypeName: string;
  scheduledAt: string;
  location: string | null;
  interviewerNotes: string | null;
  /** Lead interviewer = first of `interviewers`; kept for older consumers. */
  interviewerUserId: number | null;
  interviewerName: string | null;
  /** Full interview panel, in the order it was saved. */
  interviewers: UserPickerItem[];
  status: InterviewStatus;
  result: InterviewResult | null;
  sequenceNumber: number;
  createdAt: string;
  /** Teams join link, present once the calendar sync has succeeded for this
   * interview (null if Graph is unconfigured, the sync hasn't run yet, or it
   * failed — never a required field to render the rest of the card). */
  graphJoinUrl: string | null;
}
```

- [x] **Step 2: Render it next to the location badge**

Read the live `candidate-interviews.component.ts` first (around line 207-226). Add, right after the existing `@if (iv.location) { ... }` block:

```html
                      @if (iv.location) {
                        <span class="flex items-center gap-1">
                          <span class="material-symbols-outlined text-[13px]">location_on</span>
                          {{ iv.location }}
                        </span>
                      }
                      @if (iv.graphJoinUrl) {
                        <a [href]="iv.graphJoinUrl" target="_blank" rel="noopener"
                           class="flex items-center gap-1 text-primary hover:underline">
                          <span class="material-symbols-outlined text-[13px]">videocam</span>
                          {{ 'CANDIDATES.INTERVIEWS.JOIN_TEAMS' | translate }}
                        </a>
                      }
```

Add the translation key to both `public/i18n/fr.json` and `public/i18n/en.json`, inside the existing `CANDIDATES.INTERVIEWS` block:
- fr.json: `"JOIN_TEAMS": "Rejoindre sur Teams"`
- en.json: `"JOIN_TEAMS": "Join on Teams"`

(Check `translate` is already available in this component's imports — it already uses `| translate` extensively per the investigation, so this should be a no-op check, not a new import.)

- [x] **Step 3: Compile and verify**

Run: `npx ng build` (or the project's usual build command).
Expected: exit 0, no new TypeScript errors.

- [x] **Step 4: Mark task complete** (do NOT commit)

---

## Task 5: Manual/live verification against real Outlook/Teams

**Files:** none (no code changes — manual verification pass, matching the precedent set by both the SharePoint document and photo integrations)

- [x] **Step 1: Pick a real, valid internal interviewer and a real personal email to use as the "candidate"**

Use a real internal user with a real corporate mailbox (the same Graph app registration, so any real mailbox in this tenant works) as the lead interviewer. Use a real, reachable inbox as the candidate's `emailPersonal` for this test (e.g. your own external email) so you can actually confirm receipt of the Teams invite.

- [x] **Step 2: Create a real interview via the API or UI, with Graph credentials configured**

Ensure the running instance has `MS_GRAPH_TENANT_ID`/`MS_GRAPH_CLIENT_ID`/`MS_GRAPH_CLIENT_SECRET` set (same ones already used for SharePoint — reuse, don't reconfigure). Create an interview for a test candidate with the chosen interviewer as lead.

- [x] **Step 3: Confirm the event actually landed**

Check the lead interviewer's real Outlook calendar (or via a direct Graph `GET /users/{email}/events` call) — confirm the event exists, has the right subject/time, includes a Teams join link, and the candidate's email appears as an attendee (check the candidate inbox received the invite).

- [x] **Step 4: Edit the interview's time**

Confirm the calendar event's time updates (not a duplicate new event).

- [x] **Step 5: Change the lead interviewer**

Confirm the OLD organizer's calendar event disappears (cancelled) and a NEW event appears on the NEW lead's calendar.

- [x] **Step 6: Cancel the interview (status → CANCELLED)**

Confirm the calendar event is cancelled and attendees receive a cancellation notice, not just a silent disappearance.

- [x] **Step 7: Clean up**

Delete/cancel any real calendar artifacts left over from this test that shouldn't linger (matching the cleanup discipline used for the SharePoint document/photo live tests).

- [x] **Step 8: Report findings, then mark task complete** (do NOT commit)
