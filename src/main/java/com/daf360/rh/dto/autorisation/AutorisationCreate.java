package com.daf360.rh.dto.autorisation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A new autorisation, from the self-service form or from HR's régularisation screen.
 *
 * NOTE WHAT IS ABSENT: dureeMinutes. The server computes it from start and end — it is what
 * the monthly quota is spent against, so a caller who could send it could choose what their
 * absence costs. Same rule the congé day-count follows.
 *
 * collaborateurId is absent too: the employee is the authenticated caller. HR filing for
 * someone else uses the régularisation endpoint, which takes it explicitly and is gated on
 * SETTLE_LEAVES.
 */
@Data
public class AutorisationCreate {

    @NotNull
    private LocalDate dateAutorisation;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    /** The approver, chosen from the set the role hierarchy allows. */
    private Long responsableId;

    private Long responsableAdjointId;

    @Size(max = 2000)
    private String reason;
}
