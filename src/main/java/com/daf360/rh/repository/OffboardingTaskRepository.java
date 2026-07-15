package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OffboardingTaskRepository extends JpaRepository<OffboardingTask, Long> {

    List<OffboardingTask> findByWorkflowInstanceId(Long instanceId);

    @Query("""
            SELECT t FROM OffboardingTask t
            WHERE t.status IN ('PENDING','IN_PROGRESS')
              AND t.dueDate < :today
              AND t.slaBreachDate IS NULL
            """)
    List<OffboardingTask> findOverdueTasks(@Param("today") LocalDate today);

    @Query("""
            SELECT t FROM OffboardingTask t
            WHERE t.workflowInstance.id = :instanceId
              AND t.isBlocking = true
              AND t.status NOT IN ('DONE','SKIPPED')
            """)
    List<OffboardingTask> findBlockingIncomplete(@Param("instanceId") Long instanceId);

    List<OffboardingTask> findByStatusInAndDueDate(List<String> statuses, LocalDate dueDate);
}
