package com.daf360.rh.dto.leave;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class LeaveBalanceResponseDto {
    private Long        id;
    private Long        employeeProfileId;
    private Integer     annee;
    private String      leaveType;
    private BigDecimal  joursAcquis;
    private BigDecimal  joursPris;
    private BigDecimal  joursRestants;
    private OffsetDateTime derniereMaj;
}
