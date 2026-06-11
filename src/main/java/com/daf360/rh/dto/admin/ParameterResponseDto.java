package com.daf360.rh.dto.admin;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ParameterResponseDto {
    private Long           id;
    private Long           paysId;
    private String         cle;
    private String         valeur;
    private String         description;
    private Long           updatedBy;
    private OffsetDateTime updatedAt;
}
