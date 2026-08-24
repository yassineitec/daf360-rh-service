package com.daf360.rh.repository;

import com.daf360.rh.domain.MissionExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MissionExpenseRepository extends JpaRepository<MissionExpense, Long> {

    Optional<MissionExpense> findByMissionId(Long missionId);

    /** Batch load for the queue screens — one query instead of one per row. */
    List<MissionExpense> findByMissionIdIn(List<Long> missionIds);
}
