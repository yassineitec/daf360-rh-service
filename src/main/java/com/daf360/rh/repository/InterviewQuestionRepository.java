package com.daf360.rh.repository;

import com.daf360.rh.domain.InterviewQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {

    List<InterviewQuestion> findByInterviewTypeIdOrderByOrderIndexAscIdAsc(Long interviewTypeId);

    List<InterviewQuestion> findByInterviewTypeIdAndIsActiveTrueOrderByOrderIndexAscIdAsc(Long interviewTypeId);

    /** [typeId, active question count] for every type of a pays that has at least one. */
    @Query("""
           SELECT q.interviewTypeId, COUNT(q) FROM InterviewQuestion q, InterviewType t
            WHERE t.id = q.interviewTypeId AND t.paysId = :paysId AND q.isActive = true
            GROUP BY q.interviewTypeId
           """)
    List<Object[]> countActiveByTypeForPays(@Param("paysId") Long paysId);
}
