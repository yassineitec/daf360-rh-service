package com.daf360.rh.service.photo;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.EmployeeProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills the profile photo cache ahead of the first page view.
 *
 * <p>Why this exists: {@code servePhoto} caches lazily, so the first request for each employee
 * paid two Microsoft Graph round-trips inside the HTTP request — {@code listFiles} then
 * {@code downloadFile}. Twelve avatars to a page of the profiles grid meant the cards painted
 * immediately and the faces arrived seconds later, one by one. Nothing was broken; the work was
 * simply happening at the worst possible moment. Moving it off the request path is the whole
 * point, and it is cheap because the cost is per employee once, not per view.
 *
 * <p>Sequential, deliberately. Graph throttles, and a hundred-plus parallel lookups earn a 429
 * storm whose failures say nothing about the folders — the same reasoning as
 * {@code SharePointResolver.resolveAll}, which this pass complements: that one learns WHERE each
 * employee's folder is, this one downloads what is in it.
 *
 * <p>Runs in a background thread and is guarded by {@link #running}, so a warmup triggered from
 * the admin panel while the startup pass is still going is a no-op rather than a second flood of
 * Graph calls. Nothing waits on it and nothing fails because of it: an employee whose photo the
 * pass missed still resolves lazily on first view, exactly as before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePhotoWarmupService {

    private final EmployeeProfileRepository profileRepository;
    private final EmployeeProfileService    profileService;
    private final ProfilePhotoCache         photoCache;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Warms the cache on boot, but only when it is completely cold.
     *
     * <p>"Completely cold" rather than "incomplete" on purpose: the condition that matters is a
     * container whose storage did not survive a redeploy, and re-running a full Graph pass on
     * every restart of a healthy instance would be a self-inflicted throttling problem. A cache
     * holding even one photo is treated as warm and left to fill in lazily.
     *
     * <p>{@code ApplicationReadyEvent}, not a constructor or {@code @PostConstruct}: the pass
     * needs the datasource and the Graph client up, and must not sit in front of the port
     * opening — a slow warmup would otherwise look like a slow deploy and fail the health gate.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartupIfCold() {
        long profiles = profileRepository.count();
        if (profiles == 0) return;

        boolean anyCached = profileRepository.findAll().stream()
                .anyMatch(p -> photoCache.has(p.getId()));
        if (anyCached) {
            log.debug("Cache photo non vide — pas de prechargement au demarrage");
            return;
        }

        log.info("Cache photo vide au demarrage — prechargement de {} profils en arriere-plan",
                profiles);
        warmInBackground("demarrage");
    }

    /**
     * Starts a warmup pass unless one is already in flight.
     *
     * @param trigger what asked for it, for the log line — a pass that takes minutes needs to
     *                say why it is talking to Graph
     * @return false when a pass was already running, so the caller can say so
     */
    public boolean warmInBackground(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("Prechargement des photos deja en cours — declencheur '{}' ignore", trigger);
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                warmAll(trigger);
            } finally {
                running.set(false);
            }
        }, "photo-warmup");
        worker.setDaemon(true); // must never hold a shutdown open
        worker.start();
        return true;
    }

    /** Whether a pass is in flight. */
    public boolean isRunning() {
        return running.get();
    }

    private void warmAll(String trigger) {
        long started = System.nanoTime();
        int cached = 0, skipped = 0, missing = 0;

        for (EmployeeProfile profile : profileRepository.findAll()) {
            Long id = profile.getId();
            if (photoCache.has(id)) {
                // Still call through: an already-cached photo can have a NULL photo_url, which
                // is what kept it invisible in the first place.
                profileService.cachePhotoNow(id);
                skipped++;
                continue;
            }
            if (profileService.cachePhotoNow(id)) cached++;
            else missing++;
        }

        log.info("Prechargement des photos ({}) termine en {} s : {} mises en cache, " +
                 "{} deja presentes, {} sans photo exploitable",
                trigger, (System.nanoTime() - started) / 1_000_000_000L, cached, skipped, missing);
    }
}
