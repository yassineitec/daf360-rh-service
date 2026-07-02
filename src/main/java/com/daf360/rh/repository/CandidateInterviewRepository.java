package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.enums.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CandidateInterviewRepository extends JpaRepository<CandidateInterview, Long> {

    @Query("""
        SELECT ci FROM CandidateInterview ci
        WHERE ci.candidateId = :candidateId
        ORDER BY ci.sequenceNumber ASC
        """)
    List<CandidateInterview> findByCandidateIdOrderBySequenceNumber(
            @Param("candidateId") Long candidateId);

    boolean existsByCandidateIdAndInterviewTypeIdAndStatus(
            Long candidateId, Long interviewTypeId, InterviewStatus status);

    long countByCandidateId(Long candidateId);

    long countByInterviewTypeIdAndStatus(Long interviewTypeId, InterviewStatus status);
}
