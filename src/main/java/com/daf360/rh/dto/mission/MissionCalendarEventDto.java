package com.daf360.rh.dto.mission;

import com.daf360.rh.domain.enums.MissionScope;
import lombok.Data;

import java.time.LocalDate;

/**
 * The shell's home calendar feed. Deliberately minimal and separate from {@link MissionDto}:
 * this is public to any authenticated user for their OWN missions, and the amounts RH
 * entered are none of the calendar's business.
 */
@Data
public class MissionCalendarEventDto {
    private Long         id;
    private String       title;
    private LocalDate    startDate;
    private LocalDate    endDate;
    private MissionScope scope;
    private String       city;
    private String       countryLabel;
}
