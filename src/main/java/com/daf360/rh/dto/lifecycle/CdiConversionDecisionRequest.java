package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A decision on a CIVP → CDI request. APPROVE / REJECT by the validator (SUBMITTED) or the
 * arbiter (ESCALATED); ESCALATE by the validator only. A comment is required except to
 * approve. {@code netSalary} / {@code startDate} only count on APPROVE — empty keeps the
 * value proposed by the requester.
 */
@Data
public class CdiConversionDecisionRequest {

    public enum Action { APPROVE, REJECT, ESCALATE }

    @NotNull
    private Action action;

    @Size(max = 2000)
    private String comment;

    @DecimalMin(value = "0.001")
    private BigDecimal netSalary;

    private LocalDate startDate;
}
