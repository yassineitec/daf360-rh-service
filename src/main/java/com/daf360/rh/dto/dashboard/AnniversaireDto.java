package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;

public record AnniversaireDto(
        Long profileId,
        String fullName,
        String photoUrl,
        LocalDate dateOfBirth,
        int joursAvant
) {}
