package com.daf360.rh.repository;

import com.daf360.rh.domain.MissionStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MissionStatusHistoryRepository extends JpaRepository<MissionStatusHistory, Long> {

    List<MissionStatusHistory> findByMissionIdOrderByCreatedAtAsc(Long missionId);
}
