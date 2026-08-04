package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * The solde de tout compte as stage 6 renders it. Shape matches `OffboardingSettlement` in
 * the frontend model, which the payroll stage has been binding against since the redesign.
 */
@Data
@Builder
public class OffboardingSettlementDto {

    private List<Line> lines;
    private BigDecimal totalNet;
    private String     currency;

    @Data
    @Builder
    public static class Line {
        private Long       id;
        private String     label;
        private BigDecimal amount;
        /** The figure came from the system and nobody has overridden it. */
        private Boolean    isSuggested;
        private Integer    orderIndex;
    }
}
