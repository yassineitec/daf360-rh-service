package com.daf360.rh.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SaveDocumentTemplateDto {

    @NotNull
    private Long   paysId;

    @NotBlank
    private String category;

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    private String htmlContent;

    private String pageSize = "A4";
}
