package com.daf360.rh.repository;

import com.daf360.rh.domain.Teletravail;
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
 * The four queues, plus the reads the per-entity rules need.
 *
 * NO HARDCODED ORDER BY — the screens sort server-side from an allow-list, and an ORDER BY
 * baked into a query silently overrides the Pageable's sort (the lesson LeaveRequestRepository
 * records).
 */
public interface TeletravailRepository extends JpaRepository<Teletravail, Long> {

    // ═══ The rules ═══════════════════════════════════════════════════════════
    //
    // PENDING counts alongside APPROVED, as for autorisations: excluding it lets somebody file
    // five requests that each pass a weekly cap in isolation.

    /** Everything this employee already holds on that day — the half-day overlap test reads it. */
    @Query("""
           SELECT t FROM Teletravail t
           WHERE  t.collaborateurId = :userId
             AND  t.dateTeletravail = :date
             AND  t.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
             AND  (:excludeId IS NULL OR t.id <> :excludeId)
           """)
    List<Teletravail> findCommittedOnDay(@Param("userId") Long userId,
                                         @Param("date") LocalDate date,
                                         @Param("excludeId") Long excludeId);

    /** Days committed in a window — the weekly and monthly caps. A range, so it is sargable. */
    @Query("""
           SELECT COALESCE(SUM(t.jours), 0) FROM Teletravail t
           WHERE  t.collaborateurId = :userId
             AND  t.dateTeletravail >= :from
             AND  t.dateTeletravail <  :until
             AND  t.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
             AND  (:excludeId IS NULL OR t.id <> :excludeId)
           """)
    BigDecimal daysBetween(@Param("userId") Long userId,
                           @Param("from") LocalDate from,
                           @Param("until") LocalDate until,
                           @Param("excludeId") Long excludeId);

    /** Pending days not yet debited — what the balance must still be able to absorb. */
    @Query("""
           SELECT COALESCE(SUM(t.jours), 0) FROM Teletravail t
           WHERE  t.collaborateurId = :userId
             AND  t.etatDemande = com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE
             AND  (:excludeId IS NULL OR t.id <> :excludeId)
           """)
    BigDecimal pendingDays(@Param("userId") Long userId, @Param("excludeId") Long excludeId);

    /** Committed days from a date on — the form greys out days that are already full. */
    @Query("""
           SELECT t FROM Teletravail t
           WHERE  t.collaborateurId = :userId
             AND  t.dateTeletravail >= :from
             AND  t.etatDemande IN (com.daf360.rh.domain.enums.DemandeEtat.EN_ATTENTE,
                                    com.daf360.rh.domain.enums.DemandeEtat.VALIDE)
           """)
    List<Teletravail> findCommittedFrom(@Param("userId") Long userId, @Param("from") LocalDate from);

    // ═══ The queues ══════════════════════════════════════════════════════════

    Page<Teletravail> findByCollaborateurId(Long collaborateurId, Pageable pageable);

    /** The approver's queue — either slot matches, since either may decide. */
    @Query("""
           SELECT t FROM Teletravail t
           WHERE  (t.responsableId = :managerId OR t.responsableAdjointId = :managerId)
             AND  (:etat IS NULL OR t.etatDemande = :etat)
           """)
    Page<Teletravail> findForManager(@Param("managerId") Long managerId,
                                     @Param("etat") DemandeEtat etat,
                                     Pageable pageable);

    /** Country-wide, bounded by the caller's role pays scope (V74). */
    @Query("""
           SELECT t FROM Teletravail t
           WHERE  (:scopeAll = 1 OR t.paysId IN :scopeIds)
             AND  (:etat IS NULL OR t.etatDemande = :etat)
             AND  (:from IS NULL OR t.dateTeletravail >= :from)
             AND  (:to   IS NULL OR t.dateTeletravail <= :to)
           """)
    Page<Teletravail> findGlobal(@Param("scopeAll") int scopeAll,
                                 @Param("scopeIds") List<Long> scopeIds,
                                 @Param("etat") DemandeEtat etat,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 Pageable pageable);

    /** Everyone reporting into the caller — the ids are resolved by LeaveApproverService. */
    @Query("""
           SELECT t FROM Teletravail t
           WHERE  t.collaborateurId IN :employeeIds
             AND  (:etat IS NULL OR t.etatDemande = :etat)
           """)
    Page<Teletravail> findForEmployees(@Param("employeeIds") List<Long> employeeIds,
                                       @Param("etat") DemandeEtat etat,
                                       Pageable pageable);

    @Query("""
           SELECT t.etatDemande, COUNT(t) FROM Teletravail t
           WHERE  (t.responsableId = :managerId OR t.responsableAdjointId = :managerId)
           GROUP BY t.etatDemande
           """)
    List<Object[]> countByStateForManager(@Param("managerId") Long managerId);

    @Query("""
           SELECT t.etatDemande, COUNT(t) FROM Teletravail t
           WHERE  (:scopeAll = 1 OR t.paysId IN :scopeIds)
           GROUP BY t.etatDemande
           """)
    List<Object[]> countByStateGlobal(@Param("scopeAll") int scopeAll,
                                      @Param("scopeIds") List<Long> scopeIds);

    @Query("""
           SELECT t.etatDemande, COUNT(t) FROM Teletravail t
           WHERE  t.collaborateurId = :userId
           GROUP BY t.etatDemande
           """)
    List<Object[]> countByStateForUser(@Param("userId") Long userId);
}
