package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "break_templates")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BreakTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "regime_id", nullable = false)
    private Long regimeId;

    @Column(name = "label_fr", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelEn;

    @Column(name = "deduction_type", nullable = false, length = 20)
    @Builder.Default
    private String deductionType = "AUTO";

    @Column(name = "duration_min", nullable = false)
    private Integer durationMin;

    @Column(name = "applies_to_days", nullable = false, length = 50)
    @Builder.Default
    private String appliesToDays = "ALL";

    @Column(name = "min_work_hours_trigger", precision = 5, scale = 2)
    private BigDecimal minWorkHoursTrigger;

    @Column(name = "break_time_start")
    private java.time.LocalTime breakTimeStart;

    @Column(name = "break_time_end")
    private java.time.LocalTime breakTimeEnd;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetimeoffset")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
