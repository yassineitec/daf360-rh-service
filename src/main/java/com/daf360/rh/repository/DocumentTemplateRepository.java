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

    boolean existsByPaysIdAndNameAndLangAndIdNot(Long paysId, String name, String lang, Long excludeId);

    boolean existsByPaysIdAndNameAndLang(Long paysId, String name, String lang);

    Optional<DocumentTemplate> findFirstByPaysIdAndNameAndIsActiveTrue(Long paysId, String name);

    /** Production render lookup — one row per (pays, name, lang) so a document can be
     * generated in either language (see DocumentTemplateService.renderByName()). */
    Optional<DocumentTemplate> findFirstByPaysIdAndNameAndLangAndIsActiveTrue(Long paysId, String name, String lang);
}
