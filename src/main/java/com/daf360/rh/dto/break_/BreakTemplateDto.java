package com.daf360.rh.dto.break_;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BreakTemplateDto {
    private Long id;
    private Long paysId;
    private Long regimeId;
    private String labelFr;
    private String labelEn;
    private String deductionType;
    private Integer durationMin;
    private String appliesToDays;
    private BigDecimal minWorkHoursTrigger;
    private java.time.LocalTime breakTimeStart;
    private java.time.LocalTime breakTimeEnd;
    private Integer sortOrder;
    private Boolean isActive;
}
