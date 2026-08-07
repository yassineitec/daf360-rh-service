package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class UpdateCandidateRequest {

    /**
     * The vacancy this candidature answers — `recruitment_demands.id`.
     *
     * Tracked with an explicit "was it in the payload" flag because null is MEANINGFUL here:
     * absent means "leave the link alone", null means "detach from the vacancy". A plain
     * nullable field cannot tell those apart, and every other field on this DTO uses
     * absent-means-unchanged (see the mapper's NullValuePropertyMappingStrategy.IGNORE).
     */
    private Long recruitmentDemandId;
    /** Derived from the payload, never sent by a client. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean recruitmentDemandProvided;

    public void setRecruitmentDemandId(Long recruitmentDemandId) {
        this.recruitmentDemandId = recruitmentDemandId;
        this.recruitmentDemandProvided = true;   // Jackson only calls this when the key is present
    }

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

    @PositiveOrZero
    private BigDecimal salaireNetCandidat;

    @PositiveOrZero
    private BigDecimal salaireNetRh;
}
