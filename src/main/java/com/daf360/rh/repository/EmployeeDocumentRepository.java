package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {

    /**
     * Everything ever attached to the profile, deleted rows included.
     *
     * Kept for the callers that must not miss a row — {@code registerStagedDocument}
     * de-duplicates against it, and a soft-deleted document still occupies its
     * (profile, type, url) slot: re-registering it would create a second row pointing
     * at the same file.
     */
    List<EmployeeDocument> findByEmployeeProfileId(Long employeeProfileId);

    /** What the Documents tab shows. Served by IX_emp_docs_profile_live (V77). */
    List<EmployeeDocument> findByEmployeeProfileIdAndIsDeletedFalseOrderByUploadedAtDesc(
            Long employeeProfileId);

    List<EmployeeDocument> findByEmployeeProfileIdAndDocumentType(
            Long employeeProfileId, String documentType);

    List<EmployeeDocument> findByEmployeeProfileIdAndDocumentTypeAndIsDeletedFalse(
            Long employeeProfileId, String documentType);
}
