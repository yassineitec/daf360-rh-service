package com.daf360.rh.repository;

import com.daf360.rh.domain.Mission;
import com.daf360.rh.domain.enums.MissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    /** A manager's own list — everything they planned, whatever its status. */
    List<Mission> findByCreatedByOrderByStartDateDesc(Long createdBy);

    /** The employee's own missions (self-service). */
    List<Mission> findByEmployeeUserIdOrderByStartDateDesc(Long employeeUserId);

    /** One approval queue. Kept status-driven so RH and finance share the same method. */
    List<Mission> findByStatusOrderByStartDateAsc(MissionStatus status);

    List<Mission> findByStatusAndPaysIdOrderByStartDateAsc(MissionStatus status, Long paysId);

    /**
     * The calendar feed: the caller's APPROVED missions that OVERLAP the window, not the
     * ones that start inside it — a two-week mission straddling a month boundary must
     * appear in both months, or the employee sees a hole in the middle of their trip.
     */
    @Query("""
            SELECT m FROM Mission m
            WHERE m.employeeUserId = :userId
              AND m.status = com.daf360.rh.domain.enums.MissionStatus.APPROVED
              AND m.startDate <= :to
              AND m.endDate   >= :from
            ORDER BY m.startDate
            """)
    List<Mission> findApprovedOverlapping(@Param("userId") Long userId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /**
     * Qui est en mission un jour donné, pour TOUT un ensemble d'employés — la question que
     * pose {@code PresenceStatusJob} une fois par entité et par matin.
     *
     * <p>En lot, et non {@link #findApprovedOverlapping} appelée en boucle : la passe
     * traite des centaines de profils, et une requête par personne ferait des centaines
     * d'allers-retours pour répondre à une seule question.
     *
     * <p>Le statut est un paramètre plutôt qu'une constante dans le JPQL : la tâche dit
     * explicitement qu'elle ne s'intéresse qu'aux missions APPROUVÉES, au lieu de le
     * cacher ici. Une mission en attente d'approbation ne déplace personne.
     */
    @Query("""
            SELECT m FROM Mission m
            WHERE m.employeeUserId IN :userIds
              AND m.status    = :status
              AND m.startDate <= :day
              AND m.endDate   >= :day
            """)
    List<Mission> findApprovedCovering(@Param("userIds") java.util.Collection<Long> userIds,
                                       @Param("status") MissionStatus status,
                                       @Param("day") LocalDate day);

    /**
     * Overlapping missions for the SAME employee, used to refuse double-booking. Excludes
     * the mission being edited, and the terminal statuses — a rejected or cancelled mission
     * blocks nothing.
     *
     * {@code excludeId} is a plain id and NEVER null — {@code :param IS NULL} is not
     * portable JPQL, so the caller passes {@link #NO_EXCLUSION} when there is nothing to
     * exclude. No mission can have that id, so the comparison simply never matches.
     */
    @Query("""
            SELECT m FROM Mission m
            WHERE m.employeeUserId = :userId
              AND m.id <> :excludeId
              AND m.status IN (com.daf360.rh.domain.enums.MissionStatus.PENDING_HR,
                               com.daf360.rh.domain.enums.MissionStatus.PENDING_FINANCE,
                               com.daf360.rh.domain.enums.MissionStatus.APPROVED)
              AND m.startDate <= :to
              AND m.endDate   >= :from
            """)
    List<Mission> findOverlapping(@Param("userId") Long userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("excludeId") Long excludeId);

    /** Sentinel for {@link #findOverlapping} when no mission is being edited. */
    Long NO_EXCLUSION = -1L;
}
