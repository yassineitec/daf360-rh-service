package com.daf360.rh.dto.provisioning;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubmitEmailRequest {

    @NotBlank
    @Email
    private String ms365Email;
}
