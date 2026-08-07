package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.EmployeeDocumentMapper;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeDocumentService {

    private static final ZoneId  PARIS         = ZoneId.of("Europe/Paris");
    private static final long    MAX_BYTES      = 10L * 1024 * 1024;   // 10 MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");

    private final EmployeeDocumentRepository documentRepository;
    private final EmployeeProfileRepository  profileRepository;
    private final EmployeeDocumentMapper     mapper;
    private final AuditService               auditService;

    @Value("${app.storage-path:./uploads/hr}")
    private String storagePath;

    // ── Upload ────────────────────────────────────────────────────────────────

    public DocumentUploadResponseDto upload(Long profileId, MultipartFile file,
                                            String documentType, Authentication auth) throws IOException {
        // Profile must exist
        if (!profileRepository.existsById(profileId)) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId);
        }

        // Validate MIME type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
        }

        // Validate size
        if (file.getSize() > MAX_BYTES) {
            throw new AppException(ErrorCode.DOCUMENT_SIZE_EXCEEDED);
        }

        // Derive extension safely
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : contentTypeToExt(contentType);

        // Store to: <storagePath>/<profileId>/<uuid><ext>
        Path dir = Paths.get(storagePath, String.valueOf(profileId));
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + ext;
        Path dest = dir.resolve(filename);
        file.transferTo(dest);

        EmployeeDocument doc = EmployeeDocument.builder()
                .employeeProfileId(profileId)
                .documentType(documentType)
                .fileName(originalName)
                .fileUrl(dest.toString())
                .fileSizeKb((int) (file.getSize() / 1024))
                .verificationStatus("PENDING")
                .uploadedAt(OffsetDateTime.now(PARIS))
                .uploadedBy(extractUserId(auth))
                .build();

        EmployeeDocument saved = documentRepository.save(doc);
        auditService.log(actorId(auth), "UPLOAD_DOCUMENT", "EmployeeDocument", saved.getId(),
                null, documentType);
        return mapper.toDto(saved);
    }

    // ── Staged uploads (onboarding, before a profile exists) ──────────────────

    /**
     * Stores a file against a CANDIDATE, for the onboarding wizard.
     *
     * The signed contract is uploaded on the Contrat step, which runs before completion
     * creates the employee profile — so there is no profileId to scope it to yet. The file
     * lands under `<storagePath>/candidates/<candidateId>/` and the returned url goes into the
     * draft JSON; {@link #registerStagedDocument} turns it into a real document row at
     * completion.
     *
     * The file is NOT moved at completion: the stored path stays valid, and moving it would
     * add a failure mode that loses the document for no gain.
     */
    public Map<String, String> stageCandidateDocument(Long candidateId, MultipartFile file)
            throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new AppException(ErrorCode.DOCUMENT_SIZE_EXCEEDED);
        }

        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : contentTypeToExt(contentType);

        Path dir = Paths.get(storagePath, "candidates", String.valueOf(candidateId));
        Files.createDirectories(dir);
        Path dest = dir.resolve(UUID.randomUUID() + ext);
        file.transferTo(dest);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("url",  dest.toString());
        result.put("name", originalName != null ? originalName : dest.getFileName().toString());
        return result;
    }

    /**
     * Registers an already-stored file as a document on a profile — the completion half of
     * {@link #stageCandidateDocument}. Idempotent on (profile, type, url) so a re-run of an
     * incomplete onboarding does not create duplicate rows.
     */
    public void registerStagedDocument(Long profileId, String fileUrl, String fileName,
                                       String documentType, Long uploadedBy) {
        if (fileUrl == null || fileUrl.isBlank()) return;

        boolean exists = documentRepository.findByEmployeeProfileId(profileId).stream()
                .anyMatch(d -> documentType.equals(d.getDocumentType())
                            && fileUrl.equals(d.getFileUrl()));
        if (exists) return;

        long sizeKb = 0;
        try {
            sizeKb = Files.size(Paths.get(fileUrl)) / 1024;
        } catch (Exception ex) {
            // The row is still worth creating: the size is cosmetic, the reference is not.
            log.debug("Could not size staged document {}: {}", fileUrl, ex.getMessage());
        }

        EmployeeDocument doc = EmployeeDocument.builder()
                .employeeProfileId(profileId)
                .documentType(documentType)
                .fileName(fileName)
                .fileUrl(fileUrl)
                .fileSizeKb((int) sizeKb)
                .verificationStatus("PENDING")
                .uploadedAt(OffsetDateTime.now(PARIS))
                .uploadedBy(uploadedBy)
                .build();
        documentRepository.save(doc);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentUploadResponseDto> listDocuments(Long profileId) {
        return documentRepository.findByEmployeeProfileId(profileId)
                .stream().map(mapper::toDto).toList();
    }

    // ── Verify ────────────────────────────────────────────────────────────────

    public DocumentUploadResponseDto verify(Long docId, String status, Authentication auth) {
        EmployeeDocument doc = documentRepository.findById(docId).orElseThrow(() ->
                new AppException(ErrorCode.NOT_FOUND, "Document introuvable: id=" + docId));

        if (!Set.of("VERIFIED", "REJECTED").contains(status)) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Statut invalide — valeurs acceptées: VERIFIED, REJECTED");
        }
        doc.setVerificationStatus(status);
        EmployeeDocument saved = documentRepository.save(doc);
        auditService.log(actorId(auth), "VERIFY_DOCUMENT", "EmployeeDocument", docId, null, status);
        return mapper.toDto(saved);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String contentTypeToExt(String ct) {
        return switch (ct) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg"      -> ".jpg";
            case "image/png"       -> ".png";
            default                -> ".bin";
        };
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
