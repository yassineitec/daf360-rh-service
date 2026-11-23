package com.daf360.rh.service;

import com.daf360.rh.common.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Who may see what on a candidature, now that an interviewer needs no RH permission (V132).
 *
 * <p>Sitting on an interview panel is a right of its own: it opens that candidate's record
 * (without the salary or identity documents), the CV, the interview list and the PV form —
 * and nothing else. It is checked here, in code, rather than with a SpEL expression in
 * {@code @PreAuthorize}, because it depends on the row being read, not on the caller alone.
 *
 * <p>A CANCELLED interview grants nothing: an interviewer taken off the case loses access
 * with it.
 */
@Component
@RequiredArgsConstructor
public class InterviewAccess {

    /** What GET /api/hr/candidates/{id} accepted before V132 — full access to the record. */
    private static final Set<String> FULL_RECORD = Set.of(
            PermissionCatalog.HR_ONBOARDING, PermissionCatalog.IT_PROVISIONING);

    /** Every PV of the candidate, whoever wrote it. */
    private static final Set<String> ALL_REPORTS = Set.of(
            PermissionCatalog.RH_VALIDATE_INTERVIEW, PermissionCatalog.RH_VIEW_INTERVIEW_REPORTS);

    private static final String IS_ON_CANDIDATE_PANEL_SQL = """
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM [dbo].[candidate_interviews] ci
             WHERE ci.candidate_id = ?
               AND ci.status <> 'CANCELLED'
               AND (ci.interviewer_user_id = ?
                    OR EXISTS (SELECT 1 FROM [dbo].[candidate_interview_interviewers] p
                                WHERE p.interview_id = ci.id AND p.user_id = ?))
        ) THEN 1 ELSE 0 END
        """;

    private final JdbcTemplate jdbc;

    /** The caller's user id (JWT sub), or null. */
    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
    }

    private boolean hasAny(Set<String> permissions) {
        return permissions.stream().anyMatch(this::has);
    }

    /** The caller sits on a non-cancelled interview of this candidate. */
    public boolean isInterviewerOf(Long candidateId) {
        Long me = currentUserId();
        if (me == null || candidateId == null) return false;
        Integer hit = jdbc.queryForObject(IS_ON_CANDIDATE_PANEL_SQL, Integer.class, candidateId, me, me);
        return hit != null && hit == 1;
    }

    /** Full record (salary, identity documents, IT) — the pre-V132 rule of GET /{id}. */
    public boolean hasFullRecordAccess() {
        return hasAny(FULL_RECORD);
    }

    /** May read every PV of a candidate, not only their own. */
    public boolean canReadAllReports() {
        return hasAny(ALL_REPORTS);
    }

    /** May list a candidate's interviews: the pre-V132 code, a PV reader, or a panel member. */
    public boolean canListInterviews(Long candidateId) {
        return has(PermissionCatalog.RH_MANAGE_INTERVIEWS) || canReadAllReports()
                || isInterviewerOf(candidateId);
    }

    /** GET /candidates/{id}: full access, or a panel member (served a reduced record). */
    public void requireCandidateRead(Long candidateId) {
        if (hasFullRecordAccess() || isInterviewerOf(candidateId)) return;
        throw new AccessDeniedException("Accès refusé à ce candidat");
    }

    /** GET /candidates/{id}/cv: the pre-V132 codes, or a panel member. */
    public void requireCvRead(Long candidateId) {
        if (has(PermissionCatalog.VIEW_CANDIDATES) || has(PermissionCatalog.HR_ONBOARDING)
                || isInterviewerOf(candidateId)) return;
        throw new AccessDeniedException("Accès refusé au CV de ce candidat");
    }
}
