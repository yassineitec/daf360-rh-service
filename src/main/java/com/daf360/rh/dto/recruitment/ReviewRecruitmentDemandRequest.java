package com.daf360.rh.dto.recruitment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewRecruitmentDemandRequest {

    @NotNull
    private Boolean approved;

    @Size(max = 500)
    private String comment;
}
