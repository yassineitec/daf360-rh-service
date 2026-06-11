package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "break_legal_rules")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BreakLegalRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "label_fr", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelEn;

    @Column(name = "min_work_hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal minWorkHours;

    @Column(name = "max_work_hours", precision = 5, scale = 2)
    private BigDecimal maxWorkHours;

    @Column(name = "deduction_min", nullable = false)
    private Integer deductionMin;

    @Column(name = "applies_to_days", nullable = false, length = 50)
    @Builder.Default
    private String appliesToDays = "ALL";

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetimeoffset")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
