package com.daf360.rh.dto.offboarding;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Stage 4 — Informatique & Matériel: when the accounts go off.
 *
 * A moment rather than a day, because "désactivé le 15/09" is not actionable for IT — the
 * employee works until the end of that day. `null` leaves it unset.
 */
@Data
public class UpdateItSecurityRequestDto {

    private OffsetDateTime accountDeactivationAt;
}
