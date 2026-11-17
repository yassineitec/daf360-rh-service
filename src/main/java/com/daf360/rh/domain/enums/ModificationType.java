package com.daf360.rh.domain.enums;

/**
 * What a {@link com.daf360.rh.domain.LeaveModificationRequest} is asking for.
 *
 * The two values take DIFFERENT approval paths, which is the main thing to know about this
 * enum — see {@link com.daf360.rh.domain.enums.ModificationState}. A modification needs the
 * manager who granted the leave to agree to the new dates before HR applies them; an
 * annulation goes straight to HR.
 *
 * Carried over from the timesheet's {@code TypeModification} with the same two values, so the
 * 6 rows waiting there map across without translation.
 */
public enum ModificationType {

    /** Move the leave. Carries proposed dates and a proposed total. */
    MODIFICATION("Modification", "Modification"),

    /** Call the leave off entirely. Carries no dates — there is nothing to propose. */
    ANNULATION("Annulation", "Cancellation");

    private final String labelFr;
    private final String labelEn;

    ModificationType(String labelFr, String labelEn) {
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

    /**
     * Where a request of this type starts its life.
     *
     * ANNULATION skips the manager deliberately, as the timesheet did: handing days back takes
     * nothing away from anyone, so the manager's assent would be a step without a decision.
     */
    public ModificationState initialState() {
        return this == MODIFICATION
                ? ModificationState.PENDING_MANAGER
                : ModificationState.PENDING_HR;
    }

    /** True when the type proposes new dates — the only type for which they are required. */
    public boolean carriesDates() {
        return this == MODIFICATION;
    }
}
