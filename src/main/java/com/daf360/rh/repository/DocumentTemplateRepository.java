package com.daf360.rh.repository;

import com.daf360.rh.domain.DocumentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    List<DocumentTemplate> findByPaysIdOrderByCategoryAscNameAsc(Long paysId);

    List<DocumentTemplate> findByPaysIdAndCategoryOrderByNameAsc(Long paysId, String category);

    List<DocumentTemplate> findByPaysIdAndIsActiveTrueOrderByCategoryAscNameAsc(Long paysId);

    List<DocumentTemplate> findByPaysIdAndCategoryAndIsActiveTrueOrderByNameAsc(Long paysId, String category);

    boolean existsByPaysIdAndNameAndIdNot(Long paysId, String name, Long excludeId);

    boolean existsByPaysIdAndName(Long paysId, String name);

    Optional<DocumentTemplate> findFirstByPaysIdAndNameAndIsActiveTrue(Long paysId, String name);
}
