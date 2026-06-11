package com.daf360.rh.dto.regime;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RegimeDetailDto extends WorkingTimeRegimeResponseDto {
    private Long employeeCount;
    private Long roleCount;
}
