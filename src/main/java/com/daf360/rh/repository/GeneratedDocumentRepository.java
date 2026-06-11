package com.daf360.rh.repository;

import com.daf360.rh.domain.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, Long> {

    List<GeneratedDocument> findByEmployeeRequestIdOrderByGeneratedAtDesc(Long requestId);

    Optional<GeneratedDocument> findByVerificationCode(String code);
}
