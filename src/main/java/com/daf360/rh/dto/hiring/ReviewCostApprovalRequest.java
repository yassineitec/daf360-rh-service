package com.daf360.rh.dto.hiring;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewCostApprovalRequest {

    @Size(max = 1000)
    private String notes;
}
