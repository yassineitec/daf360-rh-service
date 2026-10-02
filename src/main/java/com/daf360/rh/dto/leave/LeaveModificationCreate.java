package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.ModificationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * A request to move or cancel an already-approved congé.
 *
 * NOTE WHAT IS ABSENT, for the same reason {@link LeaveRequestCreate} records it:
 * {@code nouveauTotal}. The server recomputes the new cost from the proposed dates, the
 * original request's category, and that country's weekend and holiday calendars. Letting the
 * browser send it would let a caller choose what their modification refunds.
 *
 * {@code collaborateurId} is absent too: the employee is the authenticated caller, and the
 * service refuses a proposal against somebody else's congé.
 */
@Data
public class LeaveModificationCreate {

    /** The approved congé being contested. */
    @NotNull
    private Long absenceId;

    @NotNull
    private ModificationType type;

    /**
     * Required for MODIFICATION, refused for ANNULATION — validated in the service rather
     * than by an annotation, because the rule depends on another field.
     */
    private LocalDate nouvelleDateDebut;

    private LocalDate nouvelleDateFin;

    /** Why. Shown to both approvers, so it is the whole case the employee gets to make. */
    @Size(max = 2000)
    private String motif;
}
