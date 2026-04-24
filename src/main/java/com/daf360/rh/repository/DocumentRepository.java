package com.daf360.rh.repository;

import com.daf360.rh.domain.Document;
import com.daf360.rh.domain.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByEmployeeIdAndIsArchivedFalse(Long employeeId);
    Optional<Document> findTopByEmployeeIdAndDocumentTypeAndIsArchivedFalseOrderByVersionDesc(
            Long employeeId, DocumentType type);
}
