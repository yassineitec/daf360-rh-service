package com.daf360.rh.dto.pipeline;

import java.util.List;

public record KanbanCandidateDto(
        Long id,
        String fullName,
        String initials,
        String photoUrl,
        String poste,
        int fitScore,
        String badge,
        String badgeType,
        String experience,
        String location,
        List<String> skills,
        String note,
        String nextEvent,
        String salary,
        boolean isUrgent,
        String stage,
        String stageLabel,
        String applicationDate,
        String email,
        String status,
        String gender,
        String contractType,
        Integer progressPercent,
        // ── Entretien column ──
        String interviewLocation,
        // ── Offre column ──
        String askedSalary,
        String proposedSalary,
        String offerExpiry,
        String offerStatus,
        /**
         * The vacancy this candidature answers (`recruitment_demands`). Null for a spontaneous
         * application — which is exactly what the card needs to show, so a candidature with no
         * opening behind it is visible rather than assumed.
         */
        Long demandId,
        String demandTitle,
        /** Préavis négocié on the offer, in calendar days; null when not discussed. */
        Integer offerNoticePeriodDays,
        /**
         * The applied grade's default préavis (V64) — what the offer form starts from, so RH
         * can see when a négociation is a derogation. Null when the grade has no default.
         */
        Integer gradeNoticePeriodDays
) {}
