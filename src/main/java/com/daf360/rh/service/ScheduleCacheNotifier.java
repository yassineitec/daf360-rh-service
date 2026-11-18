package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Tells log-service's presence scheduler that a regime changed, so it drops its cached
 * schedules instead of applying the old hours for up to 5 minutes.
 *
 * Best effort by design: called AFTER the commit (a rolled-back change must not evict
 * anything), never throws, and a blank app.log-api-base-url simply turns it off — the
 * 5-minute TTL on the log side stays the correctness floor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCacheNotifier {

    private static final String HEADER = "X-Internal-Key";
    private static final String PATH   = "/log/internal/schedules/evict";

    private final AppProperties appProperties;

    /** One employee's schedule changed (personal assignment, onboarding). */
    public void userChanged(Long userId) {
        if (userId == null) return;
        afterCommit(Map.of("userIds", List.of(userId)));
    }

    /** A regime, a role assignment or a default changed — anyone may be affected. */
    public void everyoneChanged() {
        afterCommit(Map.of());
    }

    private void afterCommit(Map<String, Object> body) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send(body); }
            });
        } else {
            send(body);
        }
    }

    private void send(Map<String, Object> body) {
        String base = appProperties.getLogApiBaseUrl();
        String key  = appProperties.getInternalApiKey();
        if (base == null || base.isBlank() || key == null || key.isBlank()) return;
        try {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            rf.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
            RestClient.builder().baseUrl(base.replaceAll("/+$", "")).requestFactory(rf).build()
                    .post().uri(PATH)
                    .header(HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not ask log-service to drop cached schedules ({}). It will catch up "
                   + "within 5 minutes.", e.getMessage());
        }
    }
}
