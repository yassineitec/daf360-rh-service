package com.daf360.rh.dto.candidate;

import com.daf360.rh.domain.enums.CandidateStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class CandidateResponse {

    private Long id;
    private Long paysId;
    private String firstName;
    private String lastName;
    private String emailPersonal;
    private String phone;
    private LocalDate dateOfBirth;
    private Long   nationalityId;
    private String nationality;
    private String gender;
    private String appliedPosition;
    private Long   appliedGradeId;
    private String appliedGrade;
    private Long   appliedDisciplineId;
    private String appliedDiscipline;
    private Long   departmentId;
    private String department;
    private Long   employmentTypeId;
    private String employmentTypeLabel;
    private LocalDate expectedStartDate;
    private CandidateStatus status;
    private String rejectionReason;
    private Long createdBy;
    private Long acceptedBy;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String notes;

    // ── CV attachment ──────────────────────────────────────────────────────────
    private String cvPath;
    private String cvOriginalName;
    private OffsetDateTime cvUploadedAt;

    /** Populated when a provisioning task exists for this candidate. */
    private ItProvisioningSummary itProvisioning;

    private Long recruitmentDemandId;
    private String recruitmentDemandJobTitle;

    private Integer experienceYears;
    private String location;

    private BigDecimal salaireNetCandidat;
    private BigDecimal salaireNetRh;

    // ── Archiving (V137) — mapped by name from the entity ──
    private OffsetDateTime archivedAt;
    /** POSITION_FILLED | DEMAND_CLOSED | MANUAL, null before V137. */
    private String archiveReason;
    private String archiveNote;
    private String archivedFromStatus;

    /** V132 — true when the caller reads this record as an interviewer only (reduced record). */
    private boolean interviewerView;
}
