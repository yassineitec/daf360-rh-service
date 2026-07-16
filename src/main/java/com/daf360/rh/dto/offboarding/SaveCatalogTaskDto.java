package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SaveCatalogTaskDto {

    @NotNull
    private Long paysId;

    @NotBlank
    private String contractType;

    @NotBlank
    private String taskCode;

    @NotBlank
    private String taskLabel;

    @NotBlank
    private String ownerRole;

    private boolean isMandatory = true;
    private boolean isBlocking  = false;

    @Min(1)
    private int slaWorkingDays = 5;

    @Min(0)
    private int orderIndex = 0;
}
