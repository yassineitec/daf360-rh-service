package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegimeOverviewStats {
    private Long totalRegimes;
    private Long employeeOverrideCount;
    private Long roleAssignmentCount;
    private Long defaultCount;
    private Long unconfiguredCount;
}
