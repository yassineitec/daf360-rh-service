package com.daf360.rh.repository;

import com.daf360.rh.domain.InterviewType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InterviewTypeRepository extends JpaRepository<InterviewType, Long> {

    List<InterviewType> findByPaysIdOrderByOrderIndexAsc(Long paysId);

    List<InterviewType> findByPaysIdAndIsActiveTrueOrderByOrderIndexAsc(Long paysId);
}
