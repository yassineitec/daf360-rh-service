package com.daf360.rh.dto.regime;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
public class WorkingTimeRegimeCreateDto {

    @NotNull
    private Long paysId;

    @NotBlank @Size(max = 50)
    private String code;

    @NotBlank @Size(max = 255)
    private String labelFr;

    @Size(max = 255)
    private String labelEn;

    /* The four fields below exist on working_time_regimes and the admin form always sent
       them, but they were missing here: MapStruct dropped them silently, so a régime was
       saved without description, break, overtime flag or daily cap. */

    @Size(max = 500)
    private String descriptionFr;

    @Size(max = 500)
    private String descriptionEn;

    /** Lunch break in minutes (0 = none). */
    @Min(0) @Max(480)
    private Integer breakDurationMin;

    private Boolean overtimeAllowed;

    /** Daily cap in hours (null = no cap). */
    @DecimalMin("0.5") @DecimalMax("24.0")
    private BigDecimal maxHoursPerDay;

    @NotNull @DecimalMin("1.0") @DecimalMax("60.0")
    private BigDecimal hoursPerWeek;

    @NotNull @Min(1) @Max(7)
    private Integer daysPerWeek;

    private LocalTime startTime;
    private LocalTime endTime;

    private Boolean isFlexible;
    private Boolean isDefault;

    /**
     * Seasonal (temporary) window. While it covers today, this regime is THE regime for
     * its entity and outranks role assignments and personal overrides.
     *
     * On update these are applied even when null, so clearing the dates in the UI really
     * removes the window — unlike the mapper's other fields, which ignore nulls. Callers
     * doing a partial update must therefore send the current values to preserve them.
     */
    private java.time.LocalDate seasonalFrom;
    private java.time.LocalDate seasonalTo;

    /**
     * IANA timezone override (e.g. Asia/Tokyo). Blank/null = inherit the entity's zone,
     * which is what almost every regime should do.
     *
     * Like the seasonal dates, this is applied on update even when null so that clearing it
     * in the UI really restores inheritance — the mapper otherwise ignores nulls.
     */
    @Size(max = 64)
    private String timezone;
}
