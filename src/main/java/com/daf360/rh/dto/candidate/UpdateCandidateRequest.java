package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.Email;
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

    @Size(max = 255)
    private String appliedPosition;

    private Long appliedGradeId;

    private Long appliedDisciplineId;

    private Long departmentId;

    @Size(max = 50)
    private String contractType;

    private LocalDate expectedStartDate;

    @Size(max = 1000)
    private String notes;
}
