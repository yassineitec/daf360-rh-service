package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.CandidateStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[candidates] in DAF360_HR.
 * Schema verified 2026-06-01 (23 columns).
 * FK_Candidate_Pays → pays, FK_Candidate_CreatedBy / AcceptedBy → Users.
 *
 * CK_Candidate_ContractType: PERMANENT | FIXED_TERM | INTERN | CONSULTANT
 * CK_Candidate_Status:       PENDING | ACCEPTED | REJECTED | IT_IN_PROGRESS |
 *                            EMAIL_RECEIVED | HR_IN_PROGRESS | HIRED | ARCHIVED
 */
@Entity
@Table(name = "candidates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "first_name", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String lastName;

    @Column(name = "email_personal", nullable = false, length = 255)
    private String emailPersonal;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nationality_id")
    private Nationality nationality;

    @Column(name = "national_id", length = 100)
    private String nationalId;

    @Column(name = "applied_position", length = 255, columnDefinition = "nvarchar(255)")
    private String appliedPosition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_grade_id")
    private Grade appliedGrade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_discipline_id")
    private Discipline appliedDiscipline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private HrDepartment department;

    @Column(name = "expected_start_date")
    private LocalDate expectedStartDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private CandidateStatus status = CandidateStatus.PENDING;

    @Column(name = "rejection_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String rejectionReason;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String notes;

    // ── CV attachment ─────────────────────────────────────────────────────────
    /** Local filesystem path to the uploaded CV file. */
    @Column(name = "cv_path", length = 500)
    private String cvPath;

    /** Original filename as uploaded by the user (for display). */
    @Column(name = "cv_original_name", length = 255)
    private String cvOriginalName;

    @Column(name = "cv_uploaded_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime cvUploadedAt;

    @Column(name = "recruitment_demand_id")
    private Long recruitmentDemandId;

    @Column(name = "employment_type_id")
    private Long employmentTypeId;

    // TODO: run migration: ALTER TABLE [dbo].[candidates] ADD fit_score INT NULL;
    // @Column(name = "fit_score") -- re-enable after migration
    @jakarta.persistence.Transient
    private Integer fitScore;
}
