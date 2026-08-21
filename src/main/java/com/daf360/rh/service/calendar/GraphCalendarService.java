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

    /**
     * Outcome of {@link #updateEvent}. Deliberately 3-state rather than a boolean:
     * the caller's self-heal logic (recreate the event if it's genuinely gone) must
     * NEVER trigger on an ambiguous/transient failure (network blip, 429 throttling,
     * 503 timeout) — doing so risks creating a duplicate event while orphaning the
     * original, which may well still be live. Only a real 404 means "safe to recreate".
     */
    public enum UpdateOutcome { SUCCESS, EVENT_NOT_FOUND, FAILED }

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

    /** Met à jour un événement existant (horaire/participants/lieu). Best-effort, ne
     * lance jamais d'exception — retourne {@link UpdateOutcome#SUCCESS} si la mise à
     * jour a réussi, {@link UpdateOutcome#EVENT_NOT_FOUND} si l'événement est
     * introuvable (404, réellement supprimé — sûr à recréer), ou
     * {@link UpdateOutcome#FAILED} pour toute autre erreur (réseau, auth, throttling,
     * timeout — transitoire ou ambiguë, ne JAMAIS recréer dans ce cas : l'événement
     * original est peut-être toujours bien vivant). */
    public UpdateOutcome updateEvent(String organizerEmail, String eventId, String subject,
            OffsetDateTime start, OffsetDateTime end, List<String> attendeeEmails, String location) {
        if (!isConfigured()) {
            log.debug("Graph non configuré — mise à jour d'événement ignorée pour {}", eventId);
            return UpdateOutcome.FAILED;
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
            return UpdateOutcome.SUCCESS;
        } catch (HttpClientErrorException.NotFound notFound) {
            log.warn("Événement calendrier {} introuvable sous {} — mise à jour ignorée, l'appelant devrait recréer l'événement", eventId, organizerEmail);
            return UpdateOutcome.EVENT_NOT_FOUND;
        } catch (Exception e) {
            log.warn("Échec de mise à jour de l'événement calendrier {}: {}", eventId, e.getMessage());
            return UpdateOutcome.FAILED;
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
        // Always included, even blank: Graph's PATCH semantics treat an OMITTED
        // property as "leave unchanged", not "clear it" — a blank location must
        // still be sent so clearing a previously-set location actually clears it
        // on the real calendar event too. Harmless on createEvent as well.
        body.put("location", Map.of("displayName", location != null ? location : ""));
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
