package com.daf360.rh.repository;

import com.daf360.rh.domain.LeaveModificationRequest;
import com.daf360.rh.domain.enums.ModificationState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The three queues a modification request moves through, plus the guard that stops two
 * proposals racing on one leave.
 *
 * NO HARDCODED ORDER BY, the lesson {@link LeaveRequestRepository} records: the screens sort
 * server-side from a column allow-list, and an {@code ORDER BY} baked into the query silently
 * wins over the {@link Pageable}'s sort, so every column header looks broken but one.
 */
public interface LeaveModificationRequestRepository
        extends JpaRepository<LeaveModificationRequest, Long> {

    /** The employee's own proposals, whatever their state. */
    Page<LeaveModificationRequest> findByCollaborateurId(Long collaborateurId, Pageable pageable);

    /**
     * The manager's queue — only ever PENDING_MANAGER, so an annulation (which starts at
     * PENDING_HR) never appears here, and neither does a modification already passed on.
     */
    Page<LeaveModificationRequest> findByResponsableIdAndEtat(
            Long responsableId, ModificationState etat, Pageable pageable);

    /**
     * The HR queue, inside the caller's country perimeter.
     *
     * {@code scopeAll} is an int rather than a boolean because the same pattern in
     * {@code findGlobal} had to be: Hibernate will not bind a Java boolean into a SQL Server
     * predicate position without a cast, so the queries that need it pass 1/0.
     *
     * A null {@code pays_id} is INCLUDED for a global-scoped caller and excluded otherwise —
     * rows migrated before pays_id was backfilled would otherwise be invisible to everyone.
     */
    @Query("""
           SELECT r FROM LeaveModificationRequest r
           WHERE  r.etat = :etat
             AND  (:scopeAll = 1 OR r.paysId IN :scopeIds)
           """)
    Page<LeaveModificationRequest> findForHr(@Param("etat") ModificationState etat,
                                             @Param("scopeAll") int scopeAll,
                                             @Param("scopeIds") List<Long> scopeIds,
                                             Pageable pageable);

    /** Queue badge counts, so the screens do not page through a list to size it. */
    long countByResponsableIdAndEtat(Long responsableId, ModificationState etat);

    @Query("""
           SELECT COUNT(r) FROM LeaveModificationRequest r
           WHERE  r.etat = :etat
             AND  (:scopeAll = 1 OR r.paysId IN :scopeIds)
           """)
    long countForHr(@Param("etat") ModificationState etat,
                    @Param("scopeAll") int scopeAll,
                    @Param("scopeIds") List<Long> scopeIds);

    /**
     * Is there already an open proposal on this leave?
     *
     * Checked before insert so the employee gets a sentence rather than a constraint
     * violation; {@code UX_amr_one_open_per_absence} (V111) is what actually guarantees it
     * under a race, since two requests can both pass this check before either commits.
     */
    @Query("""
           SELECT COUNT(r) > 0 FROM LeaveModificationRequest r
           WHERE  r.absenceId = :absenceId
             AND  r.etat IN (com.daf360.rh.domain.enums.ModificationState.PENDING_MANAGER,
                             com.daf360.rh.domain.enums.ModificationState.PENDING_HR)
           """)
    boolean hasOpenProposal(@Param("absenceId") Long absenceId);

    /** Every proposal filed against one leave — the history panel on the congé detail. */
    List<LeaveModificationRequest> findByAbsenceIdOrderByCreatedAtDesc(Long absenceId);
}
