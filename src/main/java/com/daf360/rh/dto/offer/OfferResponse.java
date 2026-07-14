package com.daf360.rh.dto.offer;

import com.daf360.rh.domain.JobOffer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Read model for a candidate's job offer. */
public record OfferResponse(
        Long id,
        Long candidateId,
        BigDecimal askedSalary,
        BigDecimal proposedSalary,
        String salaryNote,
        LocalDate expectedHireDate,
        LocalDate expiryDate,
        OffsetDateTime sentAt,
        OffsetDateTime decidedAt,
        String status,
        String rejectionReason
) {
    public static OfferResponse from(JobOffer o) {
        return new OfferResponse(
                o.getId(),
                o.getCandidateId(),
                o.getAskedSalary(),
                o.getProposedSalary(),
                o.getSalaryNote(),
                o.getExpectedHireDate(),
                o.getExpiryDate(),
                o.getSentAt(),
                o.getDecidedAt(),
                o.getStatus() != null ? o.getStatus().name() : null,
                o.getRejectionReason()
        );
    }
}
