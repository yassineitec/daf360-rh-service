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

    @Query("""
            SELECT w FROM OffboardingWorkflowInstance w
            WHERE w.paysId = :paysId
              AND w.status IN ('IN_PROGRESS','BLOCKED')
            ORDER BY w.triggerDate ASC
            """)
    List<OffboardingWorkflowInstance> findActiveByPays(@Param("paysId") Long paysId);

    @Query("""
            SELECT w FROM OffboardingWorkflowInstance w
            WHERE w.status IN ('IN_PROGRESS','BLOCKED')
            ORDER BY w.triggerDate ASC
            """)
    List<OffboardingWorkflowInstance> findAllActive();
}
