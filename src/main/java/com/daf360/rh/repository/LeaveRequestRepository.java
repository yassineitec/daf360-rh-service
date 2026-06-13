package com.daf360.rh.repository;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    /** All requests for an employee, most recent first (uses Java field startDate mapped to dateDebut). */
    List<LeaveRequest> findByEmployeeIdOrderByStartDateDesc(Long employeeId);

    /** Pending validation. */
    Page<LeaveRequest> findByEtatDemande(DemandeEtat etatDemande, Pageable pageable);

    /**
     * Total approved working days for a given employee, leave type, and year.
     * Uses Java field names (startDate → dateDebut, leaveType → type, workingDays → nombre_jours_ouvres).
     */
    @Query("SELECT SUM(lr.workingDays) FROM LeaveRequest lr " +
           "WHERE lr.employeeId = :employeeId " +
           "  AND lr.leaveType = :type " +
           "  AND lr.etatDemande = 'VALIDE' " +
           "  AND YEAR(lr.startDate) = :year")
    Integer sumApprovedDaysByTypeAndYear(@Param("employeeId") Long employeeId,
                                         @Param("type") LeaveType type,
                                         @Param("year") int year);

    /**
     * Returns active (EN_ATTENTE or VALIDE) absences that overlap [from, to] for a user.
     * Used by AbsenceService to prevent double-booking.
     */
    @Query("""
            SELECT lr FROM LeaveRequest lr
            WHERE lr.employeeId = :userId
              AND lr.etatDemande NOT IN ('REFUSE', 'ARCHIVE')
              AND lr.startDate <= :to
              AND lr.endDate   >= :from
            """)
    List<LeaveRequest> findOverlapping(@Param("userId") Long userId,
                                        @Param("from")   OffsetDateTime from,
                                        @Param("to")     OffsetDateTime to);

    @Query("SELECT COUNT(lr) FROM LeaveRequest lr " +
           "WHERE lr.etatDemande = :etat " +
           "  AND YEAR(lr.startDate) = :year " +
           "  AND MONTH(lr.startDate) = :month")
    long countByEtatDemandeAndYearMonth(@Param("etat")  DemandeEtat etat,
                                         @Param("year")  int year,
                                         @Param("month") int month);
}
