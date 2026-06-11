package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class AbsenceResponseDto {
    private Long           id;
    private Long           collaborateurId;
    private Long           responsableId;
    private Long           responsableAdjointId;
    private Long           leaveBalanceId;
    private LeaveType      leaveType;
    private LeaveCategory  category;
    private LocalDate      startDate;          // converted from OffsetDateTime (Europe/Paris)
    private LocalDate      endDate;
    private DemandeEtat    etatDemande;
    private BigDecimal     totalJours;
    private BigDecimal     workingDays;
    private String         comment;
    private String         rejectionReason;
    private Boolean        justificatif;
    private OffsetDateTime dateValidation;
    private OffsetDateTime createdAt;
}
