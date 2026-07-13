package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateCandidateRequest {

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Email @Size(max = 255)
    private String emailPersonal;

    @Size(max = 50)
    private String phone;

    private LocalDate dateOfBirth;

    private Long nationalityId;

    @Size(max = 100)
    private String nationalId;

    /** Canonical GENDER list code (MALE/FEMALE/OTHER/UNSPECIFIED); normalized on write. */
    @Size(max = 30)
    private String gender;

    @Size(max = 255)
    private String appliedPosition;

    private Long appliedGradeId;

    private Long appliedDisciplineId;

    private Long departmentId;

    private LocalDate expectedStartDate;

    @Size(max = 1000)
    private String notes;

    @Min(0) @Max(60)
    private Integer experienceYears;

    @Size(max = 150)
    private String location;
}
