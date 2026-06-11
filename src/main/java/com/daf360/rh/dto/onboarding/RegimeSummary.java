package com.daf360.rh.dto.onboarding;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class RegimeSummary {
    private Long id;
    private String code;
    private String labelFr;
    private String labelEn;
    private BigDecimal hoursPerWeek;
    private Integer daysPerWeek;
    private Boolean isFlexible;
    private Boolean isDefault;
}
