package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class ExitInterviewRequestDto {

    @NotNull
    private LocalDate conductedDate;

    private List<String> departureReasons;

    private String feedbackText;
}
