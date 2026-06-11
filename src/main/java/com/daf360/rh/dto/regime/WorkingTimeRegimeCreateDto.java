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
}
