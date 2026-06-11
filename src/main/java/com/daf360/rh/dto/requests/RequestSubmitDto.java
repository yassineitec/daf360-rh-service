package com.daf360.rh.dto.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RequestSubmitDto {

    @NotNull(message = "Le type de demande est obligatoire")
    private Long typeId;

    @Size(max = 500)
    private String attachmentUrl;

    @Size(max = 500)
    private String comment;
}
