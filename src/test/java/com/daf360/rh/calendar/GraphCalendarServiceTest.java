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
import static org.mockito.Mockito.when;

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
        GraphCalendarService.UpdateOutcome result = service.updateEvent("lead@arx.ing", "evt-123", "Entretien - Alice M",
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                List.of("candidate@example.com"), "Salle A");

        // Not configured is treated as FAILED, never EVENT_NOT_FOUND: the caller's
        // self-heal-by-recreating logic must not trigger just because Graph isn't set up.
        assertThat(result).isEqualTo(GraphCalendarService.UpdateOutcome.FAILED);
        verify(appProperties, never()).getMsGraphClientId();
    }

    @Test
    void cancelEvent_doesNothingWhenNotConfigured() {
        service.cancelEvent("lead@arx.ing", "evt-123", "Entretien annulé");

        verify(appProperties, never()).getMsGraphClientId();
    }

    @Test
    void updateEvent_returnsFailedOnGenericException() {
        // Regression test for the duplicate-event bug: a transient/unknown failure
        // (network, auth, 429 throttling, 503 timeout) must map to FAILED, never
        // EVENT_NOT_FOUND — only a real 404 may trigger the caller's self-heal.
        //
        // To exercise the real catch(Exception) branch without an actual network
        // call: appProperties is stubbed non-blank so isConfigured() passes the
        // guard, but the tenant id is an unresolved URI-template placeholder
        // ("{bad-tenant}"). That makes the very first HTTP call (the token
        // request inside getAccessToken()) fail synchronously with an
        // IllegalArgumentException from Spring's own URI-template expansion —
        // entirely offline, no real request ever leaves the JVM — while still
        // hitting the exact generic catch(Exception) branch a real transient
        // failure would.
        when(appProperties.getMsGraphTenantId()).thenReturn("{bad-tenant}");
        when(appProperties.getMsGraphClientId()).thenReturn("client-id");
        when(appProperties.getMsGraphClientSecret()).thenReturn("client-secret");

        GraphCalendarService.UpdateOutcome result = service.updateEvent("lead@arx.ing", "evt-123", "Entretien - Alice M",
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(1),
                List.of("candidate@example.com"), "Salle A");

        assertThat(result).isEqualTo(GraphCalendarService.UpdateOutcome.FAILED);
    }
}
