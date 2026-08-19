package com.daf360.rh.dto.document;

import lombok.Data;

import java.time.LocalDate;

/**
 * Corrects a document's metadata — never its bytes. Replacing a file means uploading a
 * new document and removing the old one, so the dossier keeps both facts.
 */
@Data
public class DocumentMetadataRequest {

    /** Must be one of {@code EmployeeDocumentService.DOCUMENT_TYPES}. Null = leave alone. */
    private String documentType;

    private LocalDate expirationDate;

    /**
     * Explicit "no expiry" flag: on a PATCH, a null `expirationDate` already means
     * "leave it alone", so clearing a wrongly-entered date needs its own signal.
     */
    private Boolean clearExpirationDate;

    /** Empty string clears the note; null leaves it alone. */
    private String notes;
}
