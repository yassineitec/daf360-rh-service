package com.daf360.rh.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class HolidayCreateDto {
    @NotNull  private Long      paysId;
    @NotNull  private LocalDate dateHoliday;
    @NotBlank @Size(max = 255) private String frenchLabel;
    @NotBlank @Size(max = 255) private String englishLabel;
    private Boolean isRecurring;
}
