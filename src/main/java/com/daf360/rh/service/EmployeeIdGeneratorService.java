package com.daf360.rh.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;

/**
 * Generates employee matricule numbers using the rule:
 *   [3 letters lastName][3 letters firstName][userId]
 *
 * Rules:
 * - Uppercase only
 * - Accents removed (é→E, à→A, etc.)
 * - Spaces and special characters removed
 * - userId appended to guarantee global uniqueness
 *
 * Examples:
 *   Dupont Pierre, id=125 → DUPPIE125
 *   Martin Sara,   id=78  → MARSAR78
 *   El Ayeb Sami,  id=9   → ELASAM9
 */
@Slf4j
@Service
public class EmployeeIdGeneratorService {

    /**
     * Generates the matricule for a collaborator.
     *
     * @param lastName  last name (nom de famille)
     * @param firstName first name (prénom)
     * @param userId    the auto-generated Users.id (guarantees uniqueness)
     * @return matricule string, e.g. "DUPPIE125"
     */
    public String generate(String lastName, String firstName, Long userId) {
        String lastPart  = extractPart(lastName,  3);
        String firstPart = extractPart(firstName, 3);
        String matricule = lastPart + firstPart + userId;
        log.debug("Generated matricule={} (lastName={}, firstName={}, userId={})",
                  matricule, lastName, firstName, userId);
        return matricule;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Cleans a name part: removes accents, strips non-alpha characters,
     * uppercases, takes the first {@code len} characters, pads with 'X' if shorter.
     */
    private String extractPart(String input, int len) {
        if (input == null || input.isBlank()) {
            return "X".repeat(len);
        }
        // 1. NFD decomposition separates base chars from combining diacritics
        String nfd = Normalizer.normalize(input.trim(), Normalizer.Form.NFD);
        // 2. Remove combining diacritical marks (accents)
        String noAccents = nfd.replaceAll("\\p{M}", "");
        // 3. Keep only ASCII letters (removes spaces, hyphens, apostrophes, digits…)
        String lettersOnly = noAccents.replaceAll("[^a-zA-Z]", "").toUpperCase();

        if (lettersOnly.length() >= len) {
            return lettersOnly.substring(0, len);
        }
        // Pad with 'X' if the name has fewer than `len` usable letters
        return lettersOnly + "X".repeat(len - lettersOnly.length());
    }
}
