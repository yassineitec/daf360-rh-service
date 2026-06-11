package com.daf360.rh.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ParameterDto {
    @NotNull  private Long   paysId;
    @NotBlank @Size(max = 100) private String cle;
    @NotBlank @Size(max = 500) private String valeur;
    @Size(max = 500)           private String description;
}
