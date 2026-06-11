package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RejectCandidateRequest {

    @NotBlank @Size(max = 500)
    private String rejectionReason;
}
