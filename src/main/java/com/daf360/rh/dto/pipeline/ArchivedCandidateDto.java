package com.daf360.rh.dto.pipeline;

/**
 * One card of the « Archivés » column of the Pipeline RH board (V137).
 *
 * @param archiveReason      POSITION_FILLED | DEMAND_CLOSED | MANUAL, or null for a
 *                           candidature archived before V137 (no reason was recorded then)
 * @param archiveNote        the free-text reason shown under the pill
 * @param archivedFromStatus the status it had when archived (PENDING, OFFER_SENT, HIRED…)
 * @param archivedAt         ISO local date-time; falls back to updated_at for old rows
 * @param rejectionReason    a rejection reason left on the record, shown when no archive note
 */
public record ArchivedCandidateDto(
        Long id,
        String fullName,
        String gender,
        String position,
        Long demandId,
        String demandTitle,
        String archiveReason,
        String archiveNote,
        String archivedFromStatus,
        String archivedAt,
        String archivedByName,
        String rejectionReason,
        String email
) {}
