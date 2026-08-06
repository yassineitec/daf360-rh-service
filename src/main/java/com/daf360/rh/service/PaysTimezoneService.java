package com.daf360.rh.service;

import com.daf360.rh.dto.ref.PaysTimezoneDto;
import com.daf360.rh.dto.ref.TimezoneOptionDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single source of truth for "which clock does this entity work on".
 *
 * Mirrors {@link PaysWeekendService}: [pays] is read through JdbcTemplate because there is
 * no Pays JPA entity in this service, and the answer is cached because the pointage
 * scheduler asks for it constantly.
 *
 * DELIBERATELY RETURNS NULL when an entity has no timezone. Every caller must treat that as
 * "not configured" and disable automation loudly — substituting the server's zone is exactly
 * the bug this whole mechanism exists to remove, and it would be invisible.
 *
 * IANA identifiers only (Africa/Tunis). See V67 for why offsets ('GMT+1') are not an option:
 * DST, Ramadan shifts, sub-hour zones, and ZoneId.of("GMT+1") silently having no rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaysTimezoneService {

    /**
     * Short TTL so a zone corrected in the admin panel takes effect while the person who
     * changed it is still looking at the screen. Writes call {@link #evict()} anyway; the TTL
     * covers the case where the row is edited directly in SQL.
     */
    private static final long TTL_MS = 60_000;

    private final JdbcTemplate jdbc;

    private record Snapshot(Map<Long, String> byPaysId, long loadedAt) {}

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(null);

    /** Entities already warned about, so a missing zone is reported once and not per tick. */
    private final Set<Long> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The entity's IANA zone id, or null when unset/invalid. Never a default. */
    public String timezoneFor(Long paysId) {
        if (paysId == null) return null;
        String tz = current().byPaysId().get(paysId);
        if (tz == null || tz.isBlank()) {
            if (warned.add(paysId)) {
                log.warn("Entity (pays) {} has no timezone configured — pointage automation "
                       + "cannot run for its employees. Set it in the RH admin panel, or: "
                       + "UPDATE [dbo].[pays] SET [timezone] = '<IANA id>' WHERE id = {};",
                        paysId, paysId);
            }
            return null;
        }
        return tz;
    }

    /** The entity's zone, or null when unset/invalid. */
    public ZoneId zoneFor(Long paysId) {
        String tz = timezoneFor(paysId);
        if (tz == null) return null;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            // Stored value is not a zone id. Validated on write, so this means it was set
            // directly in SQL — worth a loud, repeated complaint rather than a silent null.
            log.warn("Entity (pays) {} has an unusable timezone '{}' ({}) — automation disabled "
                   + "for its employees.", paysId, tz, e.getMessage());
            return null;
        }
    }

    /** All non-deleted entities with their configured zone — for the admin panel. */
    public List<PaysTimezoneDto> listPays() {
        return jdbc.query(
                "SELECT [id], [iso_code], [french_label], [timezone] FROM [dbo].[pays] "
              + "WHERE ISNULL([deleted], 0) = 0 ORDER BY [french_label]",
                (rs, i) -> new PaysTimezoneDto(
                        rs.getLong("id"),
                        rs.getString("iso_code"),
                        rs.getString("french_label"),
                        rs.getString("timezone"),
                        offsetLabel(rs.getString("timezone"))));
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Sets (or clears, with null/blank) an entity's zone. Validated here so a typo dies at
     * the admin form instead of in a scheduler catch block hours later.
     */
    public void setTimezone(Long paysId, String timezone) {
        String tz = (timezone == null || timezone.isBlank()) ? null : timezone.trim();
        requireValidZone(tz);
        int updated = jdbc.update("UPDATE [dbo].[pays] SET [timezone] = ? WHERE [id] = ?", tz, paysId);
        if (updated == 0) throw new AppException(ErrorCode.NOT_FOUND, "Entité introuvable: " + paysId);
        evict();
        warned.remove(paysId);
        log.info("Entity (pays) {} timezone set to {}", paysId, tz);
    }

    // ── Validation & catalogue ───────────────────────────────────────────────

    /** True for a usable IANA zone id. Rejects null/blank — callers decide if that is allowed. */
    public static boolean isValidZone(String timezone) {
        return timezone != null && !timezone.isBlank()
                && ZoneId.getAvailableZoneIds().contains(timezone.trim());
    }

    /**
     * Throws 400 unless the value is a known IANA id. A null/blank value passes: on a regime
     * it means "inherit from the entity", and on an entity it means "not configured yet".
     */
    public static void requireValidZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return;
        if (!isValidZone(timezone)) {
            throw new AppException(ErrorCode.INVALID_TIMEZONE,
                    "Fuseau horaire inconnu: '" + timezone + "'. Utilisez un identifiant IANA "
                  + "(ex. Africa/Tunis, Asia/Dubai), pas un décalage (GMT+1).");
        }
    }

    /**
     * Every selectable zone with its CURRENT offset, for the admin dropdown.
     *
     * The offset is computed for display and never stored: it is a fact about right now, and
     * it changes with DST. Sorted by offset then id so the entity's neighbours are adjacent.
     */
    public List<TimezoneOptionDto> timezoneCatalog() {
        Instant now = Instant.now();
        List<TimezoneOptionDto> out = new ArrayList<>();
        for (String id : ZoneId.getAvailableZoneIds()) {
            // Legacy three-letter ids (EST, CST…) and the SystemV/ prefix are deprecated
            // aliases; offering them invites picking a fixed-offset zone with no DST rules.
            if (!id.contains("/") || id.startsWith("SystemV/") || id.startsWith("Etc/")) continue;
            ZoneId zone = ZoneId.of(id);
            ZoneOffset offset = zone.getRules().getOffset(now);
            out.add(new TimezoneOptionDto(id, id + " (UTC" + offsetText(offset) + ")",
                    offset.getTotalSeconds()));
        }
        out.sort(Comparator.comparingInt(TimezoneOptionDto::offsetSeconds)
                .thenComparing(TimezoneOptionDto::id));
        return out;
    }

    /** Forces a reload on the next read — called after any write to [pays].timezone. */
    public void evict() {
        snapshot.set(null);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Snapshot current() {
        Snapshot cur = snapshot.get();
        if (cur != null && System.currentTimeMillis() - cur.loadedAt() < TTL_MS) return cur;

        Map<Long, String> map = new HashMap<>();
        try {
            jdbc.query("SELECT [id], [timezone] FROM [dbo].[pays]", rs -> {
                map.put(rs.getLong("id"), rs.getString("timezone"));
            });
        } catch (Exception e) {
            // A transport/schema failure must not be cached as "nobody has a timezone" for a
            // full minute: keep the previous snapshot if there is one.
            log.warn("Could not load pays timezones: {}", e.getMessage());
            if (cur != null) return cur;
        }
        Snapshot fresh = new Snapshot(map, System.currentTimeMillis());
        snapshot.set(fresh);
        return fresh;
    }

    /** "Africa/Tunis (UTC+01:00)" — display only, recomputed on every read. */
    private static String offsetLabel(String timezone) {
        if (!isValidZone(timezone)) return null;
        ZoneOffset offset = ZoneId.of(timezone.trim()).getRules().getOffset(Instant.now());
        return "UTC" + offsetText(offset);
    }

    private static String offsetText(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
    }
}
