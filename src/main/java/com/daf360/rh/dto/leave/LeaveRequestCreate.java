package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.LeaveCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * A new congé, from the self-service modal or from HR's régularisation screen.
 *
 * NOTE WHAT IS ABSENT: totalJours. The timesheet let the browser compute it — weekends and
 * holidays and all — and debited the employee's balance by whatever number arrived. The
 * server recomputes it from the dates, the category, and that country's weekend and holiday
 * calendars. See {@link com.daf360.rh.service.WorkingDayCalculator}.
 *
 * collaborateurId is also absent on purpose: the employee is the authenticated caller. HR
 * creating a request for someone else uses the régularisation endpoint, which takes it
 * explicitly and is gated on SETTLE_LEAVES.
 */
@Data
public class LeaveRequestCreate {

    /**
     * An {@code AbsenceTypes.code}, not an enum constant. The catalogue is administered by
     * HR, so the valid set is whatever is active in the table at submit time; the service
     * resolves it and rejects an unknown or retired code.
     */
    @NotBlank
    private String type;

    @NotNull
    private LeaveCategory category;

    @NotNull
    private LocalDate dateDebut;

    /** Ignored for the three single-day categories, where it is forced to dateDebut. */
    private LocalDate dateFin;

    @NotNull
    private Long responsableId;

    private Long responsableAdjointId;

    private Boolean justificatif;

    @Size(max = 2000)
    private String reason;
}
