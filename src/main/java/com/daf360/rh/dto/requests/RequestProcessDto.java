package com.daf360.rh.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RequestProcessDto {

    @NotNull
    private Long officerId;

    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED", message = "decision must be APPROVED or REJECTED")
    private String decision;

    @Size(max = 500)
    private String comment;
}
