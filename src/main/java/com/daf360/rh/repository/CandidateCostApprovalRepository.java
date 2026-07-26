package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateCostApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateCostApprovalRepository extends JpaRepository<CandidateCostApproval, Long> {

    List<CandidateCostApproval> findByPaysIdAndStatusOrderBySubmittedAtDesc(Long paysId, String status);

    List<CandidateCostApproval> findByCandidateIdOrderBySubmittedAtDesc(Long candidateId);
}
