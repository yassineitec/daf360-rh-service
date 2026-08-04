package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OffboardingChecklistItemRepository
        extends JpaRepository<OffboardingChecklistItem, Long> {

    /** All three groups in one call — the instance DTO ships them as a single list. */
    List<OffboardingChecklistItem> findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(
            Long workflowInstanceId);

    boolean existsByWorkflowInstanceIdAndGroupCodeAndItemCode(
            Long workflowInstanceId, String groupCode, String itemCode);
}
