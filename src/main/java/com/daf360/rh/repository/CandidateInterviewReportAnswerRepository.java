package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateInterviewReportAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CandidateInterviewReportAnswerRepository extends JpaRepository<CandidateInterviewReportAnswer, Long> {

    List<CandidateInterviewReportAnswer> findByReportIdInOrderByQuestionOrderAscIdAsc(Collection<Long> reportIds);

    long countByQuestionId(Long questionId);

    /** [questionId, answer count] for the given questions that have at least one answer. */
    @Query("""
           SELECT a.questionId, COUNT(a) FROM CandidateInterviewReportAnswer a
            WHERE a.questionId IN :ids GROUP BY a.questionId
           """)
    List<Object[]> countByQuestionIds(@Param("ids") Collection<Long> questionIds);

    @Modifying
    @Query("DELETE FROM CandidateInterviewReportAnswer a WHERE a.reportId = :reportId")
    void deleteByReportId(@Param("reportId") Long reportId);
}
