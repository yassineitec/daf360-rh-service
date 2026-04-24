package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.DocumentType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "documents_hr")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Builder.Default
    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Builder.Default
    @Column(nullable = false)
    private Boolean confidential = false;

    @Builder.Default
    @Column(name = "is_archived", nullable = false)
    private Boolean isArchived = false;

    @Column(name = "archive_path", length = 500)
    private String archivePath;
}
