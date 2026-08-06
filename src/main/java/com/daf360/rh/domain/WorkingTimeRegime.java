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

    /**
     * Seasonal (temporary) window. When both are set, this regime is THE regime for its
     * pays between these dates and outranks role assignments and personal overrides —
     * e.g. a summer "séance unique" schedule. NULL = a normal year-round regime.
     */
    @Column(name = "seasonal_from")
    private java.time.LocalDate seasonalFrom;

    @Column(name = "seasonal_to")
    private java.time.LocalDate seasonalTo;

    /**
     * IANA timezone this regime's hours are expressed in (e.g. Asia/Tokyo).
     *
     * NULL — the normal case — means "inherit the entity's zone" (pays.timezone). Set it only
     * to run a regime on a different clock than its entity: assigned as a time-boxed personal
     * override (regime_start_date / regime_end_date), that is how business travel is expressed
     * — contractual hours and the entity's weekends stay put, only the clock moves, and the
     * override expires by itself.
     *
     * Never an offset ('GMT+9'): see V67 for why IANA ids are the only safe representation.
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

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
