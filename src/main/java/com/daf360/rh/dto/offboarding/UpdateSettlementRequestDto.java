package com.daf360.rh.dto.offboarding;

import lombok.Data;

import java.time.LocalDate;

/** Stage 6's instance-level field: when the settlement is actually paid. */
@Data
public class UpdateSettlementRequestDto {

    private LocalDate settlementExecutionDate;
}
