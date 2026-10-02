package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface EmployeeRequestRepository extends JpaRepository<EmployeeRequest, Long> {

    // No OrderBy in these names: a static OrderBy is applied *before* the Pageable's sort, which
    // made the front's ?sort= a mere tie-breaker. The default (newest first) is now set by the
    // service when the caller sends no sort — see EmployeeRequestService.withDefaultSort.
    Page<EmployeeRequest> findByEmployeeProfileId(
            Long profileId, Pageable pageable);

    Page<EmployeeRequest> findByEmployeeProfileIdAndStatus(
            Long profileId, RequestStatus status, Pageable pageable);

    Page<EmployeeRequest> findByPaysIdAndStatus(
            Long paysId, RequestStatus status, Pageable pageable);

    /** Duplicate check: one open request per type per employee. */
    boolean existsByEmployeeProfileIdAndRequestTypeIdAndStatusIn(
            Long profileId, Long typeId, List<RequestStatus> openStatuses);

    /** SLA escalation: open requests older than a threshold. */
    @Query("""
            SELECT r FROM EmployeeRequest r
            WHERE r.status IN :statuses
              AND r.submissionDate < :threshold
            """)
    List<EmployeeRequest> findOverduePastThreshold(
            @Param("statuses")  List<RequestStatus> statuses,
            @Param("threshold") OffsetDateTime threshold);

    /** Bank-details requests per employee in last N days. */
    @Query("""
            SELECT COUNT(r) FROM EmployeeRequest r
            JOIN RequestTypeCatalog t ON r.requestTypeId = t.id
            WHERE r.employeeProfileId = :profileId
              AND t.category = 'BANK_DETAILS'
              AND r.createdAt >= :since
            """)
    long countBankDetailRequestsSince(
            @Param("profileId") Long profileId,
            @Param("since")     OffsetDateTime since);
}
