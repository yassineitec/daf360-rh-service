package com.daf360.rh.dto.break_;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateBreakTemplateRequest {
    private Long paysId;
    private Long regimeId;
    private String labelFr;
    private String labelEn;
    private String deductionType = "AUTO";
    private Integer durationMin;
    private String appliesToDays = "ALL";
    private BigDecimal minWorkHoursTrigger;
    private java.time.LocalTime breakTimeStart;  // optional — null means use min_work_hours_trigger
    private java.time.LocalTime breakTimeEnd;
    private Integer sortOrder = 0;
}
