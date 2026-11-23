package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code interviewerUserId}/{@code interviewerName} are the lead (first) interviewer,
 * kept so older consumers keep rendering; {@code interviewers} is the full panel.
 *
 * <p>V132 — the PV workflow:
 * <ul>
 *   <li>{@code reports}: the PVs this caller may read — all of them for an RH reader, only
 *       their own for a panel member;</li>
 *   <li>{@code reportCount}: how many PVs exist, readable or not, so a panel member sees
 *       « 2 / 3 PV » without reading the others;</li>
 *   <li>{@code canWriteReport}: the caller sits on the panel and the interview is still open
 *       to a PV (held, not cancelled, not validated);</li>
 *   <li>{@code canValidate}: the caller may decide PASS / FAIL now;</li>
 *   <li>{@code validatedBy*}: who decided, when, and why.</li>
 * </ul>
 */
public record CandidateInterviewDto(
        Long id,
        Long candidateId,
        Long interviewTypeId,
        String interviewTypeName,
        String interviewTypeNameEn,
        OffsetDateTime scheduledAt,
        String location,
        String interviewerNotes,
        Long interviewerUserId,
        String interviewerName,
        List<UserPickerDto> interviewers,
        String status,
        String result,
        Integer sequenceNumber,
        OffsetDateTime createdAt,
        String graphJoinUrl,
        List<InterviewReportDto> reports,
        int reportCount,
        boolean canWriteReport,
        boolean canValidate,
        Long validatedBy,
        String validatedByName,
        OffsetDateTime validatedAt,
        String validationComment
) {}
