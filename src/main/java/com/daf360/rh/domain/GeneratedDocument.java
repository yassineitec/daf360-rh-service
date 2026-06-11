package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [generated_documents] in DAF360_HR.
 * Schema verified 2026-05-31: 7 cols.
 * FK_GenDoc_Request: employee_request_id → employee_requests
 * FK_GenDoc_GeneratedBy: generated_by → Users
 */
@Entity
@Table(name = "generated_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_request_id", nullable = false)
    private Long employeeRequestId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    /** UUID-based verification code for authenticity checks. */
    @Column(name = "verification_code", length = 100)
    private String verificationCode;

    @Column(name = "generated_at", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime generatedAt;

    @Column(name = "generated_by")
    private Long generatedBy;
}
