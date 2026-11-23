package com.daf360.rh.dto.interview;

/**
 * A person the interview screens show: the scheduling picker and every panel member.
 *
 * <p>{@code profileId}, {@code photoUrl} and {@code gender} come from the user's RH profile
 * (LEFT JOIN — a user without one keeps them null) and are what the front needs to draw the
 * real photo, served by profile id at {@code /api/hr/profiles/{profileId}/photo}, then the
 * gendered placeholder, then initials.
 */
public record UserPickerDto(Long id, String fullName, Long profileId, String photoUrl, String gender) {

    /** Name only — for a user the directory no longer knows. */
    public UserPickerDto(Long id, String fullName) {
        this(id, fullName, null, null, null);
    }
}
