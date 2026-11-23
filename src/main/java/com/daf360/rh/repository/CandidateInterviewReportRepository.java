package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateInterviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateInterviewReportRepository extends JpaRepository<CandidateInterviewReport, Long> {

    List<CandidateInterviewReport> findByInterviewIdInOrderBySubmittedAtAsc(Collection<Long> interviewIds);

    Optional<CandidateInterviewReport> findByInterviewIdAndAuthorUserId(Long interviewId, Long authorUserId);

    long countByInterviewId(Long interviewId);
}
