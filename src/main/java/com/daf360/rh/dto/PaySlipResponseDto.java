package com.daf360.rh.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaySlipResponseDto {
    private Long id;
    private Long employeeId;
    private Integer monthPeriod;
    private Integer yearPeriod;
    private BigDecimal grossSalary;
    private BigDecimal contributions;
    private BigDecimal netSalary;
    private Boolean published;
    private LocalDateTime publishedAt;
}
