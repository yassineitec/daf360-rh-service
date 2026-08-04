package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OffboardingTaskRepository extends JpaRepository<OffboardingTask, Long> {

    List<OffboardingTask> findByWorkflowInstanceId(Long instanceId);

    /**
     * `workflowInstance.status IN (...)` is load-bearing: without it the daily job picked up
     * tasks belonging to VALIDATED and CANCELLED files and flipped those files back to
     * BLOCKED. Validation only requires the *blocking* tasks to be settled, so a closed file
     * routinely keeps a PENDING EXPENSE_CLOSE or INTERNAL_ANNOUNCEMENT past its due date —
     * every one of which resurrected the file the next morning.
     */
    @Query("""
            SELECT t FROM OffboardingTask t
            WHERE t.status IN ('PENDING','IN_PROGRESS')
              AND t.dueDate < :today
              AND t.slaBreachDate IS NULL
              AND t.workflowInstance.status IN ('PENDING','IN_PROGRESS','BLOCKED')
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

    /** Same reasoning as findOverdueTasks — no reminders about closed files. */
    @Query("""
            SELECT t FROM OffboardingTask t
            WHERE t.status IN :statuses
              AND t.dueDate = :dueDate
              AND t.workflowInstance.status IN ('PENDING','IN_PROGRESS','BLOCKED')
            """)
    List<OffboardingTask> findDueOnForActiveInstances(@Param("statuses") List<String> statuses,
                                                       @Param("dueDate") LocalDate dueDate);
}
