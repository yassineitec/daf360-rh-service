package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateInterviewInterviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CandidateInterviewInterviewerRepository
        extends JpaRepository<CandidateInterviewInterviewer, Long> {

    /** Ordered by id: rows are inserted lead-first, so this restores the saved order. */
    List<CandidateInterviewInterviewer> findByInterviewIdOrderByIdAsc(Long interviewId);

    /** Panel members for a batch of interviews — avoids N+1 when listing a candidate's timeline. */
    List<CandidateInterviewInterviewer> findByInterviewIdInOrderByIdAsc(Collection<Long> interviewIds);

    void deleteByInterviewId(Long interviewId);
}
