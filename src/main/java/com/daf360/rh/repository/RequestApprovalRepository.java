package com.daf360.rh.repository;

import com.daf360.rh.domain.RequestApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RequestApprovalRepository extends JpaRepository<RequestApproval, Long> {

    List<RequestApproval> findByEmployeeRequestIdOrderByDecisionDate(Long requestId);

    Optional<RequestApproval> findByEmployeeRequestIdAndLevel(Long requestId, String level);

    boolean existsByEmployeeRequestIdAndLevel(Long requestId, String level);
}
