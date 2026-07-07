package com.daf360.rh.common;

import java.util.Map;

/**
 * Normalizes any incoming gender string to the canonical GENDER configurable-list
 * value_code (MALE / FEMALE / OTHER / UNSPECIFIED — see V9 seed).
 *
 * <p>The {@code employee_profiles.gender} column is a free-form string with no DB or
 * enum constraint, and historically received three vocabularies (canonical codes,
 * French labels such as "Femme", and legacy "FEMININ"). This normalizer is the single
 * write-side guard that keeps the column canonical regardless of the client, so the
 * frontends can reliably match on {@code MALE}/{@code FEMALE}. Unknown non-blank values
 * are returned unchanged (uppercased) rather than dropped, to avoid silent data loss.
 */
public final class GenderNormalizer {
    private GenderNormalizer() {}

    public static final String MALE        = "MALE";
    public static final String FEMALE      = "FEMALE";
    public static final String OTHER       = "OTHER";
    public static final String UNSPECIFIED = "UNSPECIFIED";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("HOMME", MALE),
            Map.entry("MASCULIN", MALE),
            Map.entry("MASCULINO", MALE),
            Map.entry("MALE", MALE),
            Map.entry("M", MALE),
            Map.entry("H", MALE),

            Map.entry("FEMME", FEMALE),
            Map.entry("FEMININ", FEMALE),
            Map.entry("FÉMININ", FEMALE),
            Map.entry("FEMENINO", FEMALE),
            Map.entry("FEMALE", FEMALE),
            Map.entry("F", FEMALE),

            Map.entry("AUTRE", OTHER),
            Map.entry("OTHER", OTHER),
            Map.entry("O", OTHER),

            Map.entry("NON PRÉCISÉ", UNSPECIFIED),
            Map.entry("NON PRECISE", UNSPECIFIED),
            Map.entry("NON SPÉCIFIÉ", UNSPECIFIED),
            Map.entry("NON SPECIFIE", UNSPECIFIED),
            Map.entry("UNSPECIFIED", UNSPECIFIED),
            Map.entry("PLACEHOLDER", UNSPECIFIED),
            Map.entry("N/A", UNSPECIFIED),
            Map.entry("NA", UNSPECIFIED)
    );

    /**
     * @return the canonical code for a known alias; {@code null} for null/blank input;
     *         the trimmed, upper-cased original for an unrecognized value.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toUpperCase();
        if (key.isEmpty()) return null;
        return ALIASES.getOrDefault(key, key);
    }
}
