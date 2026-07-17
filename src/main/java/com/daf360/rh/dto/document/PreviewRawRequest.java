package com.daf360.rh.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PreviewRawRequest {
    @NotNull  private Long   paysId;
    @NotBlank private String htmlContent;
    /** Optional: if provided, real employee data is injected. */
    private Long employeeProfileId;
}
