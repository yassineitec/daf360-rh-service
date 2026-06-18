package com.daf360.rh.dto.recruitment;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Used by the candidate form dropdown to select a recruitment demand. */
@Data
@AllArgsConstructor
public class ApprovedDemandOption {
    private Long id;
    private String label;
}
