package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.OfferStatus;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CandidateCostApprovalRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.JobOfferRepository;
import com.daf360.rh.repository.RecruitmentDemandRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The number of posts of a recruitment demand, made to mean something (V137).
 *
 * <p>A post is FILLED once its offer is agreed: the candidate left the negotiation for
 * onboarding / IT provisioning, or is hired. That is the moment the person is committed —
 * waiting for the hire date would leave the demand open, and its other candidates in
 * interviews, for weeks after the post was in fact taken.
 *
 * <p>{@link #recompute} is the only writer of the counters and of the automatic close:
 * <ul>
 *   <li>filled ≥ headcount on an APPROVED demand → CLOTUREE (AUTO_FILLED), then
 *       RECRUITMENT_DEMAND_FILLED to the recruitment team and the requester. From then on no
 *       other candidature of the demand can be hired ({@link #requireOpenPost}). The ones still
 *       in recruitment (PENDING / ACCEPTED / OFFER_SENT) are NOT archived on the spot: each is
 *       archived (reason POSITION_FILLED, open offer round closed, pending finance request
 *       withdrawn) on its own deadline, its creation date + {@value #ARCHIVE_AFTER_DAYS} days
 *       ({@link #archiveDueAt}) — the board shows the days left. A candidature already past that
 *       date is archived at once. Every recount enforces the deadlines, so the reconcile job
 *       archives the others as their day comes;</li>
 *   <li>filled &lt; headcount on a demand closed AUTO_FILLED (a recruit dropped out) →
 *       APPROUVEE again and RECRUITMENT_DEMAND_REOPENED. The archived candidatures stay
 *       archived: they were told nothing, but reviving them is RH's call, not the system's.
 *       A demand closed by hand (MANUAL) is never reopened automatically.</li>
 * </ul>
 *
 * <p>Called by every service that moves a candidate in or out of a filled post, and by
 * {@code DemandFillReconcileJob} as a safety net for paths that bypass them.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DemandFillService {

    /** Statuses holding a post — the offer is agreed. */
    public static final Set<String> FILLING = Set.of(
            "IT_IN_PROGRESS", "EMAIL_RECEIVED", "HR_IN_PROGRESS", "HIRED");

    /** Statuses still in recruitment — archived on their deadline once their demand is filled. */
    public static final Set<CandidateStatus> STILL_RECRUITING = Set.of(
            CandidateStatus.PENDING, CandidateStatus.ACCEPTED, CandidateStatus.OFFER_SENT);

    /** A candidature left on a filled demand is archived this many days after its creation. */
    public static final int ARCHIVE_AFTER_DAYS = 30;

    /** When a candidature left on a filled demand is archived: its creation date + 30 days. */
    public static OffsetDateTime archiveDueAt(Candidate c) {
        return c.getCreatedAt() != null ? c.getCreatedAt().plusDays(ARCHIVE_AFTER_DAYS) : null;
    }

    /** The demand's posts are all filled and it was closed for that reason — its leftovers count down. */
    public static boolean isFilled(RecruitmentDemand d) {
        return d != null && d.getStatut() == RecruitmentDemandStatus.CLOTUREE
                && RecruitmentDemand.CLOSED_AUTO_FILLED.equals(d.getClosedReason());
    }

    private static final String COUNT_SQL = """
        SELECT COUNT(*) AS linked,
               SUM(CASE WHEN c.status IN ('IT_IN_PROGRESS','EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED')
                          OR (c.status = 'ARCHIVED'
                              AND c.archived_from_status IN ('IT_IN_PROGRESS','EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED'))
                        THEN 1 ELSE 0 END) AS filled,
               SUM(CASE WHEN c.status = 'HIRED'
                          OR (c.status = 'ARCHIVED' AND c.archived_from_status = 'HIRED')
                        THEN 1 ELSE 0 END) AS hired
          FROM [dbo].[candidates] c
         WHERE c.recruitment_demand_id = ?
        """;

    private final RecruitmentDemandRepository     demandRepo;
    private final CandidateRepository             candidateRepo;
    private final JobOfferRepository              offerRepo;
    private final CandidateCostApprovalRepository approvalRepo;
    private final NotificationRoutingService      notificationRoutingService;
    private final AuditService                    auditService;
    private final JdbcTemplate                    jdbc;
    private final EntityManager                   entityManager;

    /** Null-safe shortcut for callers holding a candidate whose demand may be unset. */
    public void recomputeFor(Long demandId, Long actorUserId) {
        if (demandId != null) recompute(demandId, actorUserId);
    }

    /**
     * Recounts the demand and applies the close / reopen rule. Idempotent: run it as often as
     * you like, it only acts on a change.
     */
    public void recompute(Long demandId, Long actorUserId) {
        // The counts are read with JDBC: pending JPA writes (a status just set by the caller)
        // must reach the database first, or the count misses the very change that triggered it.
        entityManager.flush();

        RecruitmentDemand demand = demandRepo.findById(demandId).orElse(null);
        if (demand == null) return;

        Map<String, Object> row = jdbc.queryForMap(COUNT_SQL, demandId);
        int linked = toInt(row.get("linked"));
        int filled = toInt(row.get("filled"));
        int hired  = toInt(row.get("hired"));

        boolean countersChanged = demand.getCandidateCount() != linked
                || demand.getPositionsFilled() != filled
                || demand.getHiredCount() != hired;
        demand.setCandidateCount(linked);
        demand.setPositionsFilled(filled);
        demand.setHiredCount(hired);

        if (demand.getStatut() == RecruitmentDemandStatus.APPROUVEE && filled >= demand.getHeadcount()) {
            closeAsFilled(demand, actorUserId);
            return;
        }
        if (isFilled(demand) && filled < demand.getHeadcount()) {
            reopen(demand, actorUserId, "RECRUITMENT_DEMAND_REOPENED");
            return;
        }
        if (countersChanged) {
            demand.setUpdatedAt(OffsetDateTime.now());
            demandRepo.save(demand);
        }
        // Still filled: the leftover candidatures whose 30 days are up go to the archive.
        if (isFilled(demand)) archiveOverdue(demand);
    }

    // ── Manual actions (RecruitmentDemandService) ────────────────────────────

    /**
     * RH closes a demand by hand (position cancelled, budget frozen…). Its candidatures still in
     * recruitment are archived with reason DEMAND_CLOSED; nothing reopens it automatically.
     */
    public RecruitmentDemand closeManually(Long demandId, String note, Long actorUserId) {
        RecruitmentDemand demand = demandRepo.findById(demandId)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND));
        if (demand.getStatut() != RecruitmentDemandStatus.APPROUVEE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_INVALID_TRANSITION,
                    "Seule une demande approuvée peut être clôturée (statut actuel : " + demand.getStatut() + ").");
        }
        OffsetDateTime now = OffsetDateTime.now();
        demand.setStatut(RecruitmentDemandStatus.CLOTUREE);
        demand.setClosedAt(now);
        demand.setClosedBy(actorUserId);
        demand.setClosedReason(RecruitmentDemand.CLOSED_MANUAL);
        demand.setUpdatedAt(now);
        demandRepo.save(demand);

        String reason = note != null && !note.isBlank()
                ? note.trim()
                : "Demande de recrutement clôturée";
        int archived = archiveStillRecruiting(demand, Candidate.ARCHIVE_DEMAND_CLOSED, reason, actorUserId);

        auditService.log(actor(actorUserId), "CLOSE", "RECRUITMENT_DEMAND", demandId,
                "statut=APPROUVEE", "statut=CLOTUREE; reason=MANUAL; archived=" + archived
                        + (note != null && !note.isBlank() ? "; note=" + note.trim() : ""));
        return demand;
    }

    /** Reopens a closed demand (manual or automatic close). Archived candidatures stay archived. */
    public RecruitmentDemand reopenManually(Long demandId, Long actorUserId) {
        RecruitmentDemand demand = demandRepo.findById(demandId)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND));
        if (demand.getStatut() != RecruitmentDemandStatus.CLOTUREE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_INVALID_TRANSITION,
                    "Seule une demande clôturée peut être rouverte.");
        }
        recount(demand);
        if (demand.getPositionsFilled() >= demand.getHeadcount()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Tous les postes de cette demande sont pourvus (" + demand.getPositionsFilled() + "/"
                    + demand.getHeadcount() + ") : augmentez d'abord le nombre de postes.");
        }
        reopen(demand, actorUserId, null);
        return demand;
    }

    /**
     * Changes the number of posts of an approved or closed demand. Lowering it below the posts
     * already filled is refused — those people are recruited. The close / reopen rule then
     * applies as usual: raising the headcount of a demand closed as filled reopens it, lowering
     * it to the filled count closes it.
     */
    public RecruitmentDemand changeHeadcount(Long demandId, int headcount, Long actorUserId) {
        RecruitmentDemand demand = demandRepo.findById(demandId)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND));
        if (demand.getStatut() != RecruitmentDemandStatus.APPROUVEE
                && demand.getStatut() != RecruitmentDemandStatus.CLOTUREE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_INVALID_TRANSITION,
                    "Le nombre de postes ne se modifie que sur une demande approuvée ou clôturée.");
        }
        if (headcount < 1 || headcount > 50) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Le nombre de postes doit être entre 1 et 50.");
        }
        recount(demand);
        if (headcount < demand.getPositionsFilled()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    demand.getPositionsFilled() + " poste(s) sont déjà pourvus : le nombre de postes ne peut pas être inférieur.");
        }
        int before = demand.getHeadcount();
        demand.setHeadcount(headcount);
        demand.setUpdatedAt(OffsetDateTime.now());
        demandRepo.save(demand);
        auditService.log(actor(actorUserId), "CHANGE_HEADCOUNT", "RECRUITMENT_DEMAND", demandId,
                "headcount=" + before, "headcount=" + headcount);

        // A demand closed by hand stays closed: changing its size is not a decision to reopen it.
        recompute(demandId, actorUserId);
        return demandRepo.findById(demandId).orElse(demand);
    }

    /**
     * Refuses to put another candidate on a demand that has no post left. A closed demand is
     * already refused by the attach check; this covers an approved one that is full but not yet
     * closed (a reconcile pending, or a race).
     */
    public void requireOpenPost(Long demandId) {
        if (demandId == null) return;
        RecruitmentDemand demand = demandRepo.findById(demandId).orElse(null);
        if (demand == null) return;
        if (demand.getStatut() == RecruitmentDemandStatus.CLOTUREE) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La demande de recrutement « " + demand.getJobTitle() + " » est clôturée : "
                    + "ses postes sont pourvus. Rouvrez-la ou augmentez le nombre de postes pour poursuivre.");
        }
        recount(demand);
        if (demand.getPositionsFilled() >= demand.getHeadcount()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Tous les postes de la demande « " + demand.getJobTitle() + " » sont pourvus ("
                    + demand.getPositionsFilled() + "/" + demand.getHeadcount() + "). "
                    + "Augmentez le nombre de postes pour poursuivre.");
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void recount(RecruitmentDemand demand) {
        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(COUNT_SQL, demand.getId());
        demand.setCandidateCount(toInt(row.get("linked")));
        demand.setPositionsFilled(toInt(row.get("filled")));
        demand.setHiredCount(toInt(row.get("hired")));
    }

    private void closeAsFilled(RecruitmentDemand demand, Long actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        demand.setStatut(RecruitmentDemandStatus.CLOTUREE);
        demand.setClosedAt(now);
        demand.setClosedBy(actorUserId);
        demand.setClosedReason(RecruitmentDemand.CLOSED_AUTO_FILLED);
        demand.setUpdatedAt(now);
        demandRepo.save(demand);

        // Leftovers are not archived now: each one on its own deadline (creation + 30 days).
        // Only the ones already past it go at once.
        int archived = archiveOverdue(demand);

        auditService.log(actor(actorUserId), "AUTO_CLOSE", "RECRUITMENT_DEMAND", demand.getId(),
                "statut=APPROUVEE", "statut=CLOTUREE; reason=AUTO_FILLED; filled="
                        + demand.getPositionsFilled() + "/" + demand.getHeadcount() + "; archived=" + archived);
        log.info("Recruitment demand {} filled ({}/{}): closed, {} overdue candidature(s) archived, the others on their deadline",
                demand.getId(), demand.getPositionsFilled(), demand.getHeadcount(), archived);

        dispatch("RECRUITMENT_DEMAND_FILLED", demand, actorUserId, archived);
    }

    /**
     * Archives the candidatures still in recruitment on a filled demand whose deadline
     * ({@link #archiveDueAt}) has passed.
     *
     * @return how many were archived
     */
    private int archiveOverdue(RecruitmentDemand demand) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Candidate> overdue = candidateRepo
                .findByRecruitmentDemandIdAndStatusIn(demand.getId(), STILL_RECRUITING).stream()
                .filter(c -> { OffsetDateTime due = archiveDueAt(c); return due == null || !due.isAfter(now); })
                .toList();
        if (overdue.isEmpty()) return 0;
        return archive(demand, overdue, Candidate.ARCHIVE_POSITION_FILLED,
                "Poste pourvu (" + demand.getPositionsFilled() + "/" + demand.getHeadcount() + ") — "
                        + ARCHIVE_AFTER_DAYS + " jours après la candidature", null);
    }

    private void reopen(RecruitmentDemand demand, Long actorUserId, String eventCode) {
        String before = "statut=CLOTUREE; reason=" + demand.getClosedReason();
        demand.setStatut(RecruitmentDemandStatus.APPROUVEE);
        demand.setClosedAt(null);
        demand.setClosedBy(null);
        demand.setClosedReason(null);
        demand.setUpdatedAt(OffsetDateTime.now());
        demandRepo.save(demand);

        auditService.log(actor(actorUserId), eventCode != null ? "AUTO_REOPEN" : "REOPEN",
                "RECRUITMENT_DEMAND", demand.getId(), before,
                "statut=APPROUVEE; filled=" + demand.getPositionsFilled() + "/" + demand.getHeadcount());
        if (eventCode != null) dispatch(eventCode, demand, actorUserId, 0);
    }

    /**
     * Archives every candidature of the demand still in recruitment. Each one's open offer round
     * (DRAFT awaiting finance, or SENT and with the candidate) is closed REJECTED with the reason,
     * and any finance request still PENDING for it is withdrawn from the queue — otherwise the DAF
     * would go on deciding salaries for a post that no longer exists.
     *
     * @return how many were archived
     */
    private int archiveStillRecruiting(RecruitmentDemand demand, String reasonCode, String note, Long actorUserId) {
        return archive(demand, candidateRepo.findByRecruitmentDemandIdAndStatusIn(demand.getId(), STILL_RECRUITING),
                reasonCode, note, actorUserId);
    }

    private int archive(RecruitmentDemand demand, List<Candidate> open, String reasonCode, String note, Long actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Candidate c : open) {
            String before = c.getStatus().name();
            archive(c, reasonCode, note, actorUserId, now);

            for (JobOffer round : offerRepo.findByCandidateIdOrderByRoundNumberDesc(c.getId())) {
                if (round.getSupersededAt() != null) continue;
                if (round.getStatus() == OfferStatus.DRAFT || round.getStatus() == OfferStatus.SENT) {
                    round.setStatus(OfferStatus.REJECTED);
                    round.setRejectionReason(note);
                    round.setDecidedAt(now);
                    round.setUpdatedAt(now);
                    offerRepo.save(round);
                }
            }
            approvalRepo.findByCandidateIdAndStatus(c.getId(), "PENDING").forEach(approvalRepo::delete);

            auditService.log(actor(actorUserId), "ARCHIVE", "CANDIDATE", c.getId(),
                    "status=" + before, "status=ARCHIVED; reason=" + reasonCode + "; demandId=" + demand.getId());
        }
        return open.size();
    }

    /** Stamps the archive fields — the one place a candidate becomes ARCHIVED through JPA. */
    public static void archive(Candidate c, String reasonCode, String note, Long actorUserId, OffsetDateTime now) {
        c.setArchivedFromStatus(c.getStatus() != null ? c.getStatus().name() : null);
        c.setStatus(CandidateStatus.ARCHIVED);
        c.setArchivedAt(now);
        c.setArchivedBy(actorUserId);
        c.setArchiveReason(reasonCode);
        c.setArchiveNote(note);
        c.setUpdatedAt(now);
    }

    private void dispatch(String eventCode, RecruitmentDemand demand, Long actorUserId, int archivedCount) {
        Map<String, String> vars = new HashMap<>();
        vars.put("jobTitle",      demand.getJobTitle() != null ? demand.getJobTitle() : "");
        vars.put("department",    demand.getDepartment() != null ? demand.getDepartment() : "");
        vars.put("headcount",     String.valueOf(demand.getHeadcount()));
        vars.put("filled",        String.valueOf(demand.getPositionsFilled()));
        vars.put("archivedCount", String.valueOf(archivedCount));
        vars.put("actorName",     userName(actorUserId));
        try {
            notificationRoutingService.dispatchAfterCommit(RoutingContext.builder()
                    .eventCode(eventCode)
                    .paysId(demand.getPaysId())
                    .subjectUserId(demand.getCreatedByUserId())
                    .actorUserId(actorUserId)
                    .entityType(NotificationEntityType.RECRUITMENT_DEMAND)
                    .entityId(demand.getId())
                    .templateVars(vars)
                    .build());
        } catch (Exception ex) {
            // The close itself must not fail over a notification.
            log.warn("{} notification failed for demand {}: {}", eventCode, demand.getId(), ex.getMessage());
        }
    }

    private String userName(Long userId) {
        if (userId == null) return "CORE360";
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT COALESCE(u.fullName, u.username, u.email) FROM [dbo].[Users] u WHERE u.id = ?",
                    String.class, userId);
            return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String actor(Long actorUserId) {
        return actorUserId != null ? actorUserId.toString() : "SYSTEM";
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
