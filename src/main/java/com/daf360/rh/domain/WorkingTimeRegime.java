package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Maps the [working_time_regimes] table in DAF360_HR.
 * Regime templates are managed by HR_MANAGER per country (pays_id).
 */
@Entity
@Table(name = "working_time_regimes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkingTimeRegime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelEn;

    @Column(name = "hours_per_week", nullable = false, precision = 5, scale = 2)
    private BigDecimal hoursPerWeek;

    @Column(name = "days_per_week", nullable = false)
    private Integer daysPerWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "is_flexible", nullable = false)
    @Builder.Default
    private Boolean isFlexible = false;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetime2")
    private LocalDateTime updatedAt;

    @Column(name = "description_fr", columnDefinition = "nvarchar(500)")
    private String descriptionFr;

    @Column(name = "description_en", columnDefinition = "nvarchar(500)")
    private String descriptionEn;

    @Column(name = "break_duration_min")
    @Builder.Default
    private Integer breakDurationMin = 0;

    @Column(name = "overtime_allowed", nullable = false)
    @Builder.Default
    private Boolean overtimeAllowed = false;

    @Column(name = "max_hours_per_day", precision = 4, scale = 1)
    private java.math.BigDecimal maxHoursPerDay;

    @Column(name = "updated_by")
    private Long updatedBy;
}
