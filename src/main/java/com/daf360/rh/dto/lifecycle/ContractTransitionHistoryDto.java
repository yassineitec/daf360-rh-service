package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ContractTransitionHistoryDto {

    private Long id;
    private Long contractId;
    private Long employeeProfileId;
    private String statutAvant;
    private String statutApres;
    private String actionCode;
    private Long triggeredByUserId;
    private OffsetDateTime triggeredAt;
    private String commentaire;
    private String documentReference;
    /** Who triggered it (Users.fullName) — null for the system or an unknown user. */
    private String triggeredByName;
    /** Date moves (renewal, extension): the end date before and after. Null otherwise. */
    private java.time.LocalDate previousDate;
    private java.time.LocalDate newDate;
}
