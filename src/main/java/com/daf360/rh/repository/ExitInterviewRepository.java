package com.daf360.rh.repository;

import com.daf360.rh.domain.ExitInterview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ExitInterviewRepository extends JpaRepository<ExitInterview, Long> {

    Optional<ExitInterview> findByWorkflowInstanceId(Long instanceId);

    @Query("""
            SELECT e FROM ExitInterview e
            WHERE e.isAnonymised = false
              AND e.workflowInstance.validatedAt < :cutoffDate
            """)
    List<ExitInterview> findEligibleForAnonymisation(
            @Param("cutoffDate") OffsetDateTime cutoffDate);
}
