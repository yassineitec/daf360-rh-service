package com.daf360.rh.dto.requests;

import com.daf360.rh.domain.enums.RequestCategory;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RequestTypeCreateDto {

    @NotNull  private Long   paysId;

    @NotBlank @Size(max = 100)
    private String typeCode;

    @NotBlank @Size(max = 255)
    private String displayNameFr;

    @NotBlank @Size(max = 255)
    private String displayNameEn;

    @Size(max = 500) private String description;

    @NotNull  private RequestCategory category;

    @Pattern(regexp = "L1|L2", message = "approval_level must be L1 or L2")
    private String approvalLevel = "L1";

    @Min(1) @Max(30)
    private Integer defaultSlaDays = 2;

    @Size(max = 500) private String documentTemplateUrl;
}
