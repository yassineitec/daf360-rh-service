package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateCandidateRequest {

    @NotNull
    private Long paysId;

    @NotBlank @Size(max = 100)
    private String firstName;

    @NotBlank @Size(max = 100)
    private String lastName;

    @NotBlank @Email @Size(max = 255)
    private String emailPersonal;

    @Size(max = 50)
    private String phone;

    private LocalDate dateOfBirth;

    private Long nationalityId;

    @Size(max = 100)
    private String nationalId;

    @Size(max = 255)
    private String appliedPosition;

    private Long appliedGradeId;

    private Long appliedDisciplineId;

    private Long departmentId;

    private LocalDate expectedStartDate;

    @Size(max = 1000)
    private String notes;

    private Long recruitmentDemandId;

    private Long employmentTypeId;
}
