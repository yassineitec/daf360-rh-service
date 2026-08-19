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

    // ── Suppression logique (V77) ─────────────────────────────────────────────
    //
    // Le dossier RH doit pouvoir montrer qu'une pièce a existé et qui l'a retirée,
    // donc rien n'est effacé physiquement. Le fichier reste sur le disque : c'est
    // ce qui rend la restauration possible.

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    // ── Stockage (V77) ────────────────────────────────────────────────────────

    /**
     * LOCAL | SHAREPOINT. Everything is LOCAL today — SharePoint is not integrated,
     * the field exists so the read path does not have to change when it is. With
     * SHAREPOINT, {@code fileUrl} holds the Graph webUrl and {@link #externalId} the
     * driveItem id, and the download endpoint redirects instead of streaming.
     */
    @Column(name = "storage_provider", nullable = false, length = 20,
            columnDefinition = "nvarchar(20)")
    @Builder.Default
    private String storageProvider = "LOCAL";

    @Column(name = "external_id", length = 255, columnDefinition = "nvarchar(255)")
    private String externalId;
}
