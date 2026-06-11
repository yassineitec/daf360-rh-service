package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {

    List<EmployeeDocument> findByEmployeeProfileId(Long employeeProfileId);

    List<EmployeeDocument> findByEmployeeProfileIdAndDocumentType(
            Long employeeProfileId, String documentType);
}
