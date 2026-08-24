package com.daf360.rh.repository;

import com.daf360.rh.domain.MissionChangeRequest;
import com.daf360.rh.domain.enums.MissionChangeRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MissionChangeRequestRepository extends JpaRepository<MissionChangeRequest, Long> {

    List<MissionChangeRequest> findByMissionIdOrderByCreatedAtDesc(Long missionId);

    List<MissionChangeRequest> findByStatusOrderByCreatedAtAsc(MissionChangeRequestStatus status);

    /** Used to refuse a second identical ask while the first is still open. */
    boolean existsByMissionIdAndStatus(Long missionId, MissionChangeRequestStatus status);

    /** Batch load for the self-service list, so each mission can show its pending ask. */
    List<MissionChangeRequest> findByMissionIdInOrderByCreatedAtDesc(List<Long> missionIds);
}
