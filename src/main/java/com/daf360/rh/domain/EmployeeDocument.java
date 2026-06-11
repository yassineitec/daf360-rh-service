package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps the shared [employee_documents] table in DAF360_HR.
 * Not to be confused with the rh-service-owned [documents_hr] table (Document.java).
 */
@Entity
@Table(name = "employee_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size_kb")
    private Integer fileSizeKb;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /** PENDING | VERIFIED | REJECTED */
    @Column(name = "verification_status", nullable = false, length = 30)
    @Builder.Default
    private String verificationStatus = "PENDING";

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime uploadedAt;

    @Column(name = "notes", length = 500, columnDefinition = "nvarchar(500)")
    private String notes;
}
