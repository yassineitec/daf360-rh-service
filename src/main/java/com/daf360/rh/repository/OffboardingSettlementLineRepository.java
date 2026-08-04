package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingSettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OffboardingSettlementLineRepository
        extends JpaRepository<OffboardingSettlementLine, Long> {

    List<OffboardingSettlementLine> findByWorkflowInstanceIdOrderByOrderIndexAsc(
            Long workflowInstanceId);

    boolean existsByWorkflowInstanceId(Long workflowInstanceId);
}
