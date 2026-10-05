package com.daf360.rh.repository;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.domain.enums.DemandeEtat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * The four queues, plus the three reads the quota check needs.
 *
 * NO HARDCODED ORDER BY — the lesson {@link LeaveRequestRepository} records: the screens sort
 * server-side from an allow-list, and an ORDER BY baked into a query silently overrides the
 * Pageable's sort, so every column header but one looks broken.
 */
public interface AutorisationRepository extends JpaRepository<Autorisation, Long> {

    // ═══ The quota check ═════════════════════════════════════════════════════
    //
    // All three count APPROVED and PENDING together. A pending request has not been granted,
    // but letting someone queue five of them and have each pass the check in isolation is how
    // a monthly cap is walked straight past — the timesheet counted them the same way.

    /**
     * Everything this employee already holds on that day — the ONE read the three per-day
     * checks share.
     *
     * THE OVERLAP TEST IS NOT DONE IN SQL, AND THAT IS NOT A STYLE CHOICE.
     * -------------------------------------------------------------------------------------
     * The Microsoft JDBC driver sends a LocalTime parameter as DATETIME by default
     * (`sendTimeAsDatetime`, which is true unless the connection string says otherwise), and
     * SQL Server then refuses the comparison outright:
     *
     *     The data types time and datetime are incompatible in the less than operator.
     *
     * Turning that flag off would change how EVERY time value is bound for this service —
     * BreakTemplate, WorkingTimeRegime and ParametrageHSPays all map LocalTime and work today
     * precisely because they never compare one — and the connection string is set per
     * environment, so the fix would have to land in several places that must not disagree.
     *
     * Comparing in Java instead costs nothing: an employee has at most a handful of
     * autorisations on one day, this query is covered by IX_autorisations_collaborateur, and
     * the count and the minute sum are computed from the same list — three round trips become
     * one.
     */
    @Query("""
           SELECT a FROM Autorisation a
           WHERE  a.collaborateurId = :userId
             AND  a.dateAutorisation = :date
             AND  a.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
             AND  (:excludeId IS NULL OR a.id <> :excludeId)
           """)
    List<Autorisation> findCommittedOnDay(@Param("userId") Long userId,
                                          @Param("date") LocalDate date,
                                          @Param("excludeId") Long excludeId);

    /**
     * Minutes committed in a calendar month, by date range rather than by MONTH()/YEAR().
     *
     * A range is sargable — it uses IX_autorisations_collaborateur; the function form forces a
     * scan of every row the employee has ever filed, which is what the timesheet did.
     */
    @Query("""
           SELECT COALESCE(SUM(a.dureeMinutes), 0) FROM Autorisation a
           WHERE  a.collaborateurId = :userId
             AND  a.dateAutorisation >= :monthStart
             AND  a.dateAutorisation <  :nextMonth
             AND  a.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
             AND  (:excludeId IS NULL OR a.id <> :excludeId)
           """)
    long minutesInMonth(@Param("userId") Long userId,
                        @Param("monthStart") LocalDate monthStart,
                        @Param("nextMonth") LocalDate nextMonth,
                        @Param("excludeId") Long excludeId);

    // ═══ The queues ══════════════════════════════════════════════════════════

    /** The employee's own. */
    Page<Autorisation> findByCollaborateurId(Long collaborateurId, Pageable pageable);

    /** The approver's queue — either slot matches, since either may decide. */
    @Query("""
           SELECT a FROM Autorisation a
           WHERE  (a.responsableId = :managerId OR a.responsableAdjointId = :managerId)
             AND  (:etat IS NULL OR a.etatDemande = :etat)
           """)
    Page<Autorisation> findForManager(@Param("managerId") Long managerId,
                                      @Param("etat") DemandeEtat etat,
                                      Pageable pageable);

    /** Country-wide, bounded by the caller's role pays scope (V74). */
    @Query("""
           SELECT a FROM Autorisation a
           WHERE  (:scopeAll = 1 OR a.paysId IN :scopeIds)
             AND  (:etat IS NULL OR a.etatDemande = :etat)
             AND  (:from IS NULL OR a.dateAutorisation >= :from)
             AND  (:to   IS NULL OR a.dateAutorisation <= :to)
           """)
    Page<Autorisation> findGlobal(@Param("scopeAll") int scopeAll,
                                  @Param("scopeIds") List<Long> scopeIds,
                                  @Param("etat") DemandeEtat etat,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  Pageable pageable);

    /** Everyone reporting into the caller — the ids are resolved by LeaveApproverService. */
    @Query("""
           SELECT a FROM Autorisation a
           WHERE  a.collaborateurId IN :employeeIds
             AND  (:etat IS NULL OR a.etatDemande = :etat)
           """)
    Page<Autorisation> findForEmployees(@Param("employeeIds") List<Long> employeeIds,
                                        @Param("etat") DemandeEtat etat,
                                        Pageable pageable);

    /** Badge counts, so a screen does not page through a list to size it. */
    @Query("""
           SELECT a.etatDemande, COUNT(a) FROM Autorisation a
           WHERE  (a.responsableId = :managerId OR a.responsableAdjointId = :managerId)
           GROUP BY a.etatDemande
           """)
    List<Object[]> countByStateForManager(@Param("managerId") Long managerId);

    @Query("""
           SELECT a.etatDemande, COUNT(a) FROM Autorisation a
           WHERE  (:scopeAll = 1 OR a.paysId IN :scopeIds)
           GROUP BY a.etatDemande
           """)
    List<Object[]> countByStateGlobal(@Param("scopeAll") int scopeAll,
                                      @Param("scopeIds") List<Long> scopeIds);

    @Query("""
           SELECT a.etatDemande, COUNT(a) FROM Autorisation a
           WHERE  a.collaborateurId = :userId
           GROUP BY a.etatDemande
           """)
    List<Object[]> countByStateForUser(@Param("userId") Long userId);
}
