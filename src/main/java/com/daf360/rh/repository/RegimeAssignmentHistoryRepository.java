package com.daf360.rh.repository;

import com.daf360.rh.domain.RegimeAssignmentHistory;
import com.daf360.rh.domain.enums.AssignmentLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegimeAssignmentHistoryRepository extends JpaRepository<RegimeAssignmentHistory, Long> {

    List<RegimeAssignmentHistory> findByAssignmentLevelAndTargetIdOrderByChangedAtDesc(
            AssignmentLevel level, Long targetId);
}
