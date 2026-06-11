package com.daf360.rh.dto.admin;

import lombok.Data;

import java.time.LocalDate;

@Data
public class HolidayResponseDto {
    private Long      id;
    private Long      paysId;
    private LocalDate dateHoliday;
    private String    frenchLabel;
    private String    englishLabel;
    private Boolean   isRecurring;
}
