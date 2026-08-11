package com.daf360.rh.repository;

import com.daf360.rh.domain.DocumentTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentTemplateVersionRepository extends JpaRepository<DocumentTemplateVersion, Long> {

    List<DocumentTemplateVersion> findByTemplateIdOrderByVersionNumberDesc(Long templateId);

    int countByTemplateId(Long templateId);
}
