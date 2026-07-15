package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateAssetReturnDto {

    @NotNull
    private Long workflowInstanceId;

    private Long taskId;

    @NotBlank
    private String assetDescription;

    private String assetType = "IT";

    @NotNull
    private LocalDate expectedReturnDate;
}
