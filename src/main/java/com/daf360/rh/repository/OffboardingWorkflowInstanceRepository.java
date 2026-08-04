package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingWorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OffboardingWorkflowInstanceRepository
        extends JpaRepository<OffboardingWorkflowInstance, Long> {

    Optional<OffboardingWorkflowInstance> findByEmployeeProfileIdAndStatusNotIn(
            Long profileId, List<String> excludedStatuses);

    List<OffboardingWorkflowInstance> findByPaysIdAndStatus(Long paysId, String status);

    List<OffboardingWorkflowInstance> findByStatus(String status);

    List<OffboardingWorkflowInstance> findBySlaBreachFlagTrue();

    /*
     * "Active" here means NOT ARCHIVED, not "in flight".
     *
     * These two used to return only IN_PROGRESS and BLOCKED, which meant a validated or
     * cancelled file never reached the list at all: the board's Clôture column was
     * permanently empty, the "Validés" KPI was stuck at 0, and choosing VALIDATED in the
     * status filter always gave nothing. The page filters client-side, so it needs the whole
     * non-archived population to filter from. ARCHIVED stays out — that is the retention
     * state, and its whole point is to leave the working set.
     */
    @Query("""
            SELECT w FROM OffboardingWorkflowInstance w
            WHERE w.paysId = :paysId
              AND w.status <> 'ARCHIVED'
            ORDER BY w.triggerDate ASC
            """)
    List<OffboardingWorkflowInstance> findActiveByPays(@Param("paysId") Long paysId);

    @Query("""
            SELECT w FROM OffboardingWorkflowInstance w
            WHERE w.status <> 'ARCHIVED'
            ORDER BY w.triggerDate ASC
            """)
    List<OffboardingWorkflowInstance> findAllActive();
}
