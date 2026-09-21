package com.daf360.rh.dto.offer;

import com.daf360.rh.domain.JobOffer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Read model for one offer ROUND (V98). */
public record OfferResponse(
        Long id,
        Long candidateId,
        /** 1-based negotiation round. */
        Integer roundNumber,
        /** Set once a later round replaced this one; null ⇒ this is the current offer. */
        OffsetDateTime supersededAt,
        BigDecimal askedSalary,
        BigDecimal proposedSalary,
        String salaryNote,
        /** Préavis négocié in calendar days (V68); null when it was not discussed. */
        Integer noticePeriodDays,
        String noticePeriodNote,
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
                o.getRoundNumber(),
                o.getSupersededAt(),
                o.getAskedSalary(),
                o.getProposedSalary(),
                o.getSalaryNote(),
                o.getNoticePeriodDays(),
                o.getNoticePeriodNote(),
                o.getExpectedHireDate(),
                o.getExpiryDate(),
                o.getSentAt(),
                o.getDecidedAt(),
                o.getStatus() != null ? o.getStatus().name() : null,
                o.getRejectionReason()
        );
    }
}
