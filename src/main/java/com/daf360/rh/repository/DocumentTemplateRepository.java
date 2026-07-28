package com.daf360.rh.repository;

import com.daf360.rh.domain.DocumentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    // ── Per-pays queries (regular users + admin scoped to one entity) ──────────

    List<DocumentTemplate> findByPaysIdOrderByCategoryAscNameAsc(Long paysId);

    List<DocumentTemplate> findByPaysIdAndCategoryOrderByNameAsc(Long paysId, String category);

    List<DocumentTemplate> findByPaysIdAndIsActiveTrueOrderByCategoryAscNameAsc(Long paysId);

    List<DocumentTemplate> findByPaysIdAndCategoryAndIsActiveTrueOrderByNameAsc(Long paysId, String category);

    // ── Cross-entity queries (admin all-entities view, paysId = null) ──────────

    List<DocumentTemplate> findAllByOrderByPaysIdAscCategoryAscNameAsc();

    List<DocumentTemplate> findAllByIsActiveTrueOrderByPaysIdAscCategoryAscNameAsc();

    List<DocumentTemplate> findByCategoryOrderByPaysIdAscNameAsc(String category);

    List<DocumentTemplate> findByCategoryAndIsActiveTrueOrderByPaysIdAscNameAsc(String category);

    boolean existsByPaysIdAndNameAndIdNot(Long paysId, String name, Long excludeId);

    boolean existsByPaysIdAndName(Long paysId, String name);

    Optional<DocumentTemplate> findFirstByPaysIdAndNameAndIsActiveTrue(Long paysId, String name);
}
