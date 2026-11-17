package com.daf360.rh.domain.enums;

/**
 * Where a {@link com.daf360.rh.domain.LeaveModificationRequest} stands.
 *
 * SEPARATE FROM {@link DemandeEtat}, AND DELIBERATELY SO
 * -----------------------------------------------------------------------------
 * A congé has one approval; a modification has two, and the two stages are decided by
 * different people for different reasons. Reusing {@code DemandeEtat} would have forced
 * EN_ATTENTE to mean "waiting for the manager" in one row and "waiting for HR" in the next,
 * with nothing in the value to say which — and the manager's queue would then have to infer
 * its own contents from the type.
 *
 * English names here, unlike {@code DemandeEtat}: these states are new rather than migrated,
 * so nothing already spells them in French.
 *
 * Ported from the timesheet's {@code EtatModification} (EN_ATTENTE_RESPONSABLE,
 * EN_ATTENTE_RH, VALIDE, REFUSE) — same four states, renamed.
 */
public enum ModificationState {

    /**
     * Waiting on the manager who granted the original leave. MODIFICATION only — an
     * ANNULATION never passes through here.
     */
    PENDING_MANAGER("En attente du responsable", "Pending manager"),

    /**
     * Waiting on HR. Every request reaches this state: a modification after the manager
     * agrees, an annulation from the moment it is filed. This is the stage that writes —
     * the leave and the balance both change when it is approved, and nowhere else.
     */
    PENDING_HR("En attente RH", "Pending HR"),

    /** Applied. The leave has been moved or cancelled and the balance adjusted. */
    APPROVED("Validé", "Approved"),

    /** Refused at either stage. The leave is untouched. */
    REJECTED("Refusé", "Rejected");

    private final String labelFr;
    private final String labelEn;

    ModificationState(String labelFr, String labelEn) {
        this.labelFr = labelFr;
        this.labelEn = labelEn;
    }

    public String getLabelFr() {
        return labelFr;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabel(String lang) {
        return "fr".equalsIgnoreCase(lang) ? labelFr : labelEn;
    }

    /** Still open — shown in a queue, and blocks a second proposal on the same leave. */
    public boolean isPending() {
        return this == PENDING_MANAGER || this == PENDING_HR;
    }
}
