package com.daf360.rh.domain;

/**
 * How a role's country (pays) visibility is resolved — see V74__role_pays_scope.sql.
 *
 * The distinction between OWN and LIST is the whole point of this enum. A role shared by
 * several countries whose holders must each stay inside their own (e.g. "Responsable GC",
 * held by Tunisian and Egyptian users alike) is OWN. A role that genuinely spans several
 * countries for every holder (e.g. an "RH" role covering TN + EG + UAE) is LIST. Listing
 * countries against a shared role instead of using OWN would let each holder read the
 * others' data.
 *
 * Mirrored as a string in the DB (CK_Roles_PaysScopeMode) and in the portal, which resolves
 * the mode into a concrete country set when it mints a token.
 */
public enum PaysScopeMode {

    /** The user's own Users.pays_id — one country, different per user. The default, and the
     *  behaviour of every role before V74. Rows in RolePaysScope are ignored (but kept). */
    OWN,

    /** Exactly the countries in RolePaysScope — the same set for every holder. */
    LIST,

    /** Every country, no filter. Equivalent to the legacy Roles.showAll = 1. */
    ALL;

    /** Lenient parse for DB/DTO strings: unknown, blank or null all fall back to the
     *  narrowest mode rather than accidentally widening what a role can see. */
    public static PaysScopeMode from(String raw) {
        if (raw == null || raw.isBlank()) return OWN;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OWN;
        }
    }
}
