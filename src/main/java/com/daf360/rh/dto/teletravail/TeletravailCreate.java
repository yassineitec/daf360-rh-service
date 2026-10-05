package com.daf360.rh.dto.teletravail;

import com.daf360.rh.domain.enums.LeaveCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * A new télétravail request, from the self-service form or from HR's régularisation.
 *
 * NOTE WHAT IS ABSENT: the number of days. The server derives it from the category — it is
 * what the balance and the caps are spent against, so a caller who could send it could
 * choose what their day costs. collaborateurId is absent too: the employee is the caller;
 * HR filing for someone else uses the régularisation endpoint, gated on SETTLE_LEAVES.
 */
@Data
public class TeletravailCreate {

    @NotNull
    private LocalDate dateTeletravail;

    /** FULL_DAY, HALF_DAY_MORNING or HALF_DAY_AFTERNOON. Null is read as FULL_DAY. */
    private LeaveCategory category;

    private Long responsableId;

    private Long responsableAdjointId;

    @Size(max = 2000)
    private String reason;
}
