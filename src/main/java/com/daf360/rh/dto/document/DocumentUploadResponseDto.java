package com.daf360.rh.dto.document;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class DocumentUploadResponseDto {
    private Long   id;
    private Long   employeeProfileId;
    private String documentType;
    private String fileName;
    /**
     * Storage location, NOT a browsable link: for LOCAL it is a path on the service's
     * disk. The UI must go through `GET …/documents/{id}/download`, never this value.
     */
    private String fileUrl;
    private Integer fileSizeKb;
    private String verificationStatus;
    private OffsetDateTime uploadedAt;

    // ── Exposed since the Documents tab rebuild ───────────────────────────────
    // Both columns existed on the table from the start and were simply never mapped,
    // which is why the tab could not track an expiring CIN or carry a comment.
    private LocalDate expirationDate;
    private String    notes;

    private Long   uploadedBy;
    private String uploadedByName;

    /** LOCAL | SHAREPOINT (V77). Lets the UI label where a document actually lives. */
    private String storageProvider;

    // ── Soft delete (V77) ─────────────────────────────────────────────────────
    // Normally false: the list endpoint filters deleted rows out. Present so an
    // explicit "corbeille" read can show what was removed and by whom.
    private Boolean isDeleted;
    private OffsetDateTime deletedAt;
    private Long deletedBy;
    private String deletedByName;
}
