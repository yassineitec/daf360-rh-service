package com.daf360.rh.dto.onboarding;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the recruitment process already decided, gathered for the onboarding "Contrat"
 * step — READ-ONLY.
 *
 * The point is that RH confirms terms rather than retyping them from memory: the salary and
 * the préavis were agreed with the candidate in the offer, and Finance may have counter-
 * proposed. Before this existed, all of it lived in three tables nobody looked at again and
 * the figures were re-entered by hand on the profile.
 *
 * Nothing here is writable. What RH agrees to is captured in the request's own fields and
 * frozen on the contract; this block is the evidence they are deciding against.
 */
@Data
@Builder
public class OnboardingRecruitmentDto {

    /** The offer as it stands. Null when no offer was ever sent (a direct hire). */
    private OfferSummary offer;

    /** Net salary the candidate declared for themselves. */
    private BigDecimal candidateDeclaredNetSalary;
    /** Net salary RH assessed — also what a Finance counter-proposal overwrites. */
    private BigDecimal hrAssessedNetSalary;

    /**
     * The applied grade's default préavis (V64) — shown so a negotiated figure reads as a
     * derogation rather than an arbitrary number. Null when the grade has no default.
     */
    private Integer gradeNoticePeriodDays;

    /** Newest first. Empty when the hire never went through a cost approval. */
    private List<CostApprovalSummary> costApprovals;

    /** Interview feedback, in sequence order. Only rounds that recorded notes. */
    private List<InterviewNote> interviewNotes;

    @Data
    @Builder
    public static class OfferSummary {
        private BigDecimal      askedSalary;
        private BigDecimal      proposedSalary;
        private String          salaryNote;
        /** Préavis négocié, in calendar days; null when it was not discussed. */
        private Integer         noticePeriodDays;
        private String          noticePeriodNote;
        private LocalDate       expectedHireDate;
        private LocalDate       expiryDate;
        /** SENT | ACCEPTED | REJECTED | EXPIRED */
        private String          status;
        private OffsetDateTime  sentAt;
        private OffsetDateTime  decidedAt;
    }

    @Data
    @Builder
    public static class CostApprovalSummary {
        private String          status;          // PENDING | APPROVED | REJECTED
        private BigDecimal      salaireNetRh;
        private BigDecimal      salaireNetCandidat;
        /** Set on a rejection — the figure Finance countered with. */
        private BigDecimal      contrePropSalaire;
        private String          approvalNotes;
        private OffsetDateTime  submittedAt;
        private OffsetDateTime  approvedAt;
    }

    @Data
    @Builder
    public static class InterviewNote {
        private Integer         sequenceNumber;
        private String          interviewType;
        /** PASS | FAIL | PENDING … */
        private String          result;
        private String          interviewerNotes;
        private OffsetDateTime  scheduledAt;
    }
}
