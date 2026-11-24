package com.daf360.rh.dto.recruitment;

/**
 * One candidature of a recruitment demand, as its detail page lists it (« Candidats » section
 * and KPI row). Light on purpose: the page is open to whoever may see the demand — a manager
 * who raised it included — not only to the recruitment team, so it carries no salary, no
 * contact detail beyond the e-mail, nothing from the candidate's file.
 *
 * @param status          the candidature's status (PENDING, OFFER_SENT, HIRED, ARCHIVED…)
 * @param createdAt       ISO local date-time of the candidature
 * @param archiveDueDate  yyyy-MM-dd when every post is filled and this candidature is still in
 *                        recruitment: the day it is archived (creation + 30 days); else null
 * @param archivedAt      ISO local date-time, for an archived one
 */
public record DemandCandidateDto(
        Long id,
        String fullName,
        String gender,
        String email,
        String status,
        String createdAt,
        String archiveReason,
        String archivedFromStatus,
        String archivedAt,
        String archiveDueDate,
        String rejectionReason
) {}
