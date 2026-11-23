package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateEmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateEmailTemplateRepository extends JpaRepository<CandidateEmailTemplate, Long> {

    List<CandidateEmailTemplate> findByPaysIdOrderByCodeAsc(Long paysId);

    Optional<CandidateEmailTemplate> findByCodeAndPaysId(String code, Long paysId);

    Optional<CandidateEmailTemplate> findByCodeAndPaysIdAndIsActiveTrue(String code, Long paysId);
}
