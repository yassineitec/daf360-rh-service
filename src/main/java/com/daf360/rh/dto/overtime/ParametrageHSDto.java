package com.daf360.rh.dto.overtime;

import lombok.Builder;
import lombok.Data;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Data @Builder
public class ParametrageHSDto {
    private Long   idParametrage;
    private Long   paysId;
    private String paysIsoCode;
    private String paysLabel;
    private String typeCalculHs;         // WEEKEND_ONLY / AFTER_WORK_HOURS / MIXTE
    private LocalTime heureDebutTravail;
    private LocalTime heureFinTravail;
    private String jourDebutSemaine;
    private String jourFinSemaine;
    private Boolean actif;
    private OffsetDateTime dateCreation;
    private OffsetDateTime dateModification;
}
