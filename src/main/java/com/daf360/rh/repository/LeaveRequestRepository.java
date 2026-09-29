package com.daf360.rh.repository;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Queries behind the congé screens.
 *
 * The timesheet's AbsenceRepository drove its lists through a generic {@code PaginationFilter}
 * list assembled into a predicate at runtime. That is not reproduced: it moved the filter
 * grammar out of the type system, so a mistyped field name failed at query time rather than
 * compile time. Each screen here gets a named method whose parameters say exactly what it
 * filters on, and every optional parameter follows the same `:p IS NULL OR ...` shape so one
 * method serves a screen whether or not its filters are filled in.
 */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    // ── The employee's own ───────────────────────────────────────────────────

    /**
     * "My requests" — every state, newest first. ARCHIVE is included on purpose: an employee
     * whose request was cancelled should still find it, otherwise it looks like it was never
     * submitted.
     */
    Page<LeaveRequest> findByCollaborateurIdOrderByCreatedAtDesc(Long collaborateurId, Pageable pageable);

    /**
     * Ranges that block a new request, for the date picker.
     *
     * REFUSE and ARCHIVE are excluded — a refused request holds no days, so it must not stop
     * the employee asking again for the same dates, which was the point of refusing it.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
              AND l.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
              AND l.dateFin >= :from
            ORDER BY l.dateDebut
            """)
    List<LeaveRequest> findBlockingRanges(@Param("userId") Long userId, @Param("from") LocalDate from);

    /**
     * Whether a proposed range collides with one the employee already holds.
     *
     * Overlap, not containment: two ranges collide when each starts before the other ends.
     * {@code excludeId} lets an edit ignore the row being edited, which would otherwise
     * always collide with itself.
     */
    @Query("""
            SELECT COUNT(l) FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
              AND (:excludeId IS NULL OR l.id <> :excludeId)
              AND l.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
              AND l.dateDebut <= :to
              AND l.dateFin   >= :from
            """)
    long countOverlapping(@Param("userId") Long userId,
                          @Param("from") LocalDate from,
                          @Param("to") LocalDate to,
                          @Param("excludeId") Long excludeId);

    // ── The manager's queue ──────────────────────────────────────────────────

    /**
     * A manager's pending queue. Matches on either approver slot, because the adjoint exists
     * precisely so that either may decide.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE (l.responsableId = :managerId OR l.responsableAdjointId = :managerId)
              AND (:etat IS NULL OR l.etatDemande = :etat)
              AND (:from IS NULL OR l.dateDebut >= :from)
              AND (:to   IS NULL OR l.dateFin   <= :to)
              AND (:collaborateurId IS NULL OR l.collaborateurId = :collaborateurId)
              AND (:type IS NULL OR l.type = :type)
              AND l.type NOT IN :hiddenTypes
            ORDER BY l.createdAt DESC
            """)
    Page<LeaveRequest> findForManager(@Param("managerId") Long managerId,
                                      @Param("etat") DemandeEtat etat,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to,
                                      @Param("collaborateurId") Long collaborateurId,
                                      @Param("type") String type,
                                      @Param("hiddenTypes") List<String> hiddenTypes,
                                      Pageable pageable);

    /**
     * The ids a bulk approve would act on, resolved under the same predicate as the queue
     * above so that "approve all" means exactly the rows the manager is looking at.
     */
    @Query("""
            SELECT l.id FROM LeaveRequest l
            WHERE (l.responsableId = :managerId OR l.responsableAdjointId = :managerId)
              AND l.etatDemande = com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE
              AND (:from IS NULL OR l.dateDebut >= :from)
              AND (:to   IS NULL OR l.dateFin   <= :to)
              AND (:collaborateurId IS NULL OR l.collaborateurId = :collaborateurId)
              AND (:type IS NULL OR l.type = :type)
            """)
    List<Long> findPendingIdsForManager(@Param("managerId") Long managerId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("collaborateurId") Long collaborateurId,
                                        @Param("type") String type);

    // ── HR and country-wide ──────────────────────────────────────────────────

    /**
     * Country-wide history. Filters on the request's stored pays_id rather than the
     * employee's current country — see the field comment on LeaveRequest.paysId.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE (:paysId IS NULL OR l.paysId = :paysId)
              AND (:etat   IS NULL OR l.etatDemande = :etat)
              AND (:type   IS NULL OR l.type = :type)
              AND (:from   IS NULL OR l.dateDebut >= :from)
              AND (:to     IS NULL OR l.dateFin   <= :to)
              AND (:collaborateurId IS NULL OR l.collaborateurId = :collaborateurId)
            ORDER BY l.dateDebut DESC
            """)
    Page<LeaveRequest> findGlobal(@Param("paysId") Long paysId,
                                  @Param("etat") DemandeEtat etat,
                                  @Param("type") String type,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("collaborateurId") Long collaborateurId,
                                  Pageable pageable);

    /** Everyone reporting into a manager, whatever the state — the team history screen. */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE l.collaborateurId IN :employeeIds
              AND (:etat IS NULL OR l.etatDemande = :etat)
              AND (:from IS NULL OR l.dateDebut >= :from)
              AND (:to   IS NULL OR l.dateFin   <= :to)
              AND l.type NOT IN :hiddenTypes
            ORDER BY l.dateDebut DESC
            """)
    Page<LeaveRequest> findForEmployees(@Param("employeeIds") List<Long> employeeIds,
                                        @Param("etat") DemandeEtat etat,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("hiddenTypes") List<String> hiddenTypes,
                                        Pageable pageable);

    // ── Régularisations ──────────────────────────────────────────────────────

    /**
     * Congés FILED BY someone other than the person they are for.
     *
     * `createdBy <> collaborateurId` is the whole definition — see LeaveRequest.createdBy
     * for why this replaced the timesheet's `reason LIKE '%FORCE%'`.
     *
     * `:actorId` null means "every régularisation", which is what an HR administrator wants;
     * passing a value narrows it to the ones that person filed, which is what the timesheet
     * showed. The screen chooses, so the same query serves both without a second one.
     *
     * Ordered by creation and not by leave date: this list is a record of acts, read newest
     * first, while a congé list is read by when the person is away.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE l.createdBy IS NOT NULL
              AND l.createdBy <> l.collaborateurId
              AND (:actorId IS NULL OR l.createdBy = :actorId)
              AND (:etat IS NULL OR l.etatDemande = :etat)
              AND (:collaborateurId IS NULL OR l.collaborateurId = :collaborateurId)
              AND (:from IS NULL OR l.dateDebut >= :from)
              AND (:to   IS NULL OR l.dateFin   <= :to)
            ORDER BY l.createdAt DESC
            """)
    Page<LeaveRequest> findSettled(@Param("actorId") Long actorId,
                                   @Param("etat") DemandeEtat etat,
                                   @Param("collaborateurId") Long collaborateurId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   Pageable pageable);

    // ── Calendar ─────────────────────────────────────────────────────────────

    /**
     * The employee's own leave OVERLAPPING a window, for the home calendar.
     *
     * Overlap, not "starts inside": a two-week congé straddling a month boundary must
     * appear in both months, or the employee sees a hole in the middle of their own leave.
     * The same reasoning MissionRepository.findApprovedOverlapping records.
     *
     * Pending is included alongside approved — an employee planning around a request they
     * have made wants to see it before someone signs it off. The caller distinguishes them
     * by state so the calendar can render a pending congé differently.
     */
    @Query("""
            SELECT l FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
              AND l.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
              AND l.dateDebut <= :to
              AND l.dateFin   >= :from
            ORDER BY l.dateDebut
            """)
    List<LeaveRequest> findOverlapping(@Param("userId") Long userId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    // ── Aggregates ───────────────────────────────────────────────────────────

    /**
     * Counts per state for the header tiles. Returns rows of {state, count} rather than a
     * projection so a state with no rows is simply absent and the caller fills the zero —
     * counting four separate queries would be four round trips for one tile row.
     */
    @Query("""
            SELECT l.etatDemande, COUNT(l) FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
            GROUP BY l.etatDemande
            """)
    List<Object[]> countByStateForUser(@Param("userId") Long userId);

    /**
     * Days actually taken, per type, for one employee in one year.
     *
     * VALIDE only: a pending request has not been debited, and a refused one never will be.
     * Used by the HR statistics screen and by any future move to derived balances — which is
     * why it is expressed here rather than summed in Java.
     */
    @Query("""
            SELECT l.type, COALESCE(SUM(l.totalJours), 0) FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
              AND l.etatDemande = com.daf360.rh.domain.enums.DemandeEtat.VALIDE
              AND (:year IS NULL OR YEAR(l.dateDebut) = :year)
            GROUP BY l.type
            """)
    List<Object[]> sumApprovedDaysByType(@Param("userId") Long userId, @Param("year") Integer year);

    /** The same figure for one type, used by the balance check on submission. */
    @Query("""
            SELECT COALESCE(SUM(l.totalJours), 0) FROM LeaveRequest l
            WHERE l.collaborateurId = :userId
              AND l.type = :type
              AND l.etatDemande = com.daf360.rh.domain.enums.DemandeEtat.VALIDE
              AND YEAR(l.dateDebut) = :year
            """)
    BigDecimal sumApprovedDays(@Param("userId") Long userId,
                               @Param("type") String type,
                               @Param("year") int year);
}
