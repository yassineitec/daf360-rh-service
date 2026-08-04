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
}
