package com.daf360.rh.dto.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TypeContratDto {
    private Long   id;
    private String code;
    private String labelFr;
    private String labelEn;
    private Boolean isActive;
}
