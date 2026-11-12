package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/hr/contracts/{id}/decisions — what can be decided on a contract right now.
 * {@code moment} null = nothing to decide (CDI past its trial, closed contract…).
 */
@Data
@Builder
public class ContractDecisionOptionsDto {

    /** TRIAL_END | CONTRACT_END | null */
    private String moment;
    /** The deadline the decision is about: effective trial end, or planned contract end. */
    private LocalDate deadline;
    private List<Option> options;

    @Data
    @Builder
    public static class Option {
        /** CONTRACT_DECISION list value id — what /decide takes. */
        private Long   id;
        /** ContractDecisionAction code. */
        private String action;
        private String labelFr;
        private String labelEn;
        private boolean requiresDate;
        /** Why the choice cannot be taken now (null = available). Shown, not hidden, so RH knows. */
        private String blockedReason;
    }
}
