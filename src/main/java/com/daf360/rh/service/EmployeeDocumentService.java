package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.dto.document.DocumentMetadataRequest;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.EmployeeDocumentMapper;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    private static final long    MAX_BYTES      = 10L * 1024 * 1024;   // 10 MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");

    /**
     * The one document-type vocabulary.
     *
     * There used to be three: the profile tab's select, the onboarding wizard's French slot
     * labels, and `CONTRACT_SIGNED` — written by {@code OnboardingService.linkContractDocument}
     * and absent from the select, so the document it created could not be re-selected. Nothing
     * validated any of it because the column is a free NVARCHAR.
     *
     * CONTRACT_SIGNED is in the list precisely because the app already writes it; leaving it
     * out would make the service reject its own data.
     */
    public static final Set<String> DOCUMENT_TYPES = Set.of(
            "CONTRACT", "CONTRACT_SIGNED", "AMENDMENT",
            "ID_CARD", "PASSPORT", "RESIDENCE_PERMIT",
            "DIPLOMA", "CV", "MEDICAL_CERTIFICATE",
            "RIB", "CNSS", "TAX_FORM",
            "PHOTO", "RESIGNATION", "DISCHARGE", "OTHER");


    private static final String USER_NAME_SQL =
            "SELECT NULLIF(LTRIM(RTRIM(u.fullName)), '') FROM [dbo].[Users] u WHERE u.id = ?";

    private final EmployeeDocumentRepository documentRepository;
    private final EmployeeProfileRepository  profileRepository;
    private final EmployeeDocumentMapper     mapper;
    private final AuditService               auditService;
    private final JdbcTemplate               jdbc;

    @Value("${app.storage-path:./uploads/hr}")
    private String storagePath;

    // ── Upload ────────────────────────────────────────────────────────────────

    public DocumentUploadResponseDto upload(Long profileId, MultipartFile file,
                                            String documentType, LocalDate expirationDate,
                                            String notes, Authentication auth) throws IOException {
        // Profile must exist
        if (!profileRepository.existsById(profileId)) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId);
        }

        String type = normaliseType(documentType);

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
                .documentType(type)
                .fileName(originalName)
                .fileUrl(dest.toString())
                .fileSizeKb((int) (file.getSize() / 1024))
                .expirationDate(expirationDate)
                .notes(trimToNull(notes))
                .verificationStatus("PENDING")
                // Stamped in the JVM's own zone, which the containers pin to UTC on purpose.
                // This used to force Europe/Paris, which put every upload an hour or two off
                // the rest of the platform's timestamps.
                .uploadedAt(OffsetDateTime.now())
                .uploadedBy(extractUserId(auth))
                .storageProvider("LOCAL")
                .isDeleted(false)
                .build();

        EmployeeDocument saved = documentRepository.save(doc);
        auditService.log(actorId(auth), "UPLOAD_DOCUMENT", "EmployeeDocument", saved.getId(),
                null, type);
        return toDto(saved);
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
     *
     * The check deliberately looks at deleted rows too: a soft-deleted document still points
     * at this file, so skipping the insert is right — re-creating it would resurrect a pièce
     * RH deliberately removed.
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
                .uploadedAt(OffsetDateTime.now())
                .uploadedBy(uploadedBy)
                .storageProvider("LOCAL")
                .isDeleted(false)
                .build();
        documentRepository.save(doc);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentUploadResponseDto> listDocuments(Long profileId) {
        return documentRepository
                .findByEmployeeProfileIdAndIsDeletedFalseOrderByUploadedAtDesc(profileId)
                .stream().map(this::toDto).toList();
    }

    /** The corbeille: what was removed from this dossier, and by whom. */
    @Transactional(readOnly = true)
    public List<DocumentUploadResponseDto> listDeletedDocuments(Long profileId) {
        return documentRepository.findByEmployeeProfileId(profileId).stream()
                .filter(d -> Boolean.TRUE.equals(d.getIsDeleted()))
                .map(this::toDto)
                .toList();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Streams the stored bytes. This is the ONLY way a document can be opened: `file_url`
     * holds a path on the service's disk, nothing serves the upload directory statically,
     * and before this endpoint existed an uploaded document could never be read back.
     *
     * @param profileId scoping is part of the contract, not decoration — without it any
     *                  authenticated user could walk document ids across dossiers.
     */
    @Transactional(readOnly = true)
    public DownloadPayload download(Long profileId, Long docId) {
        EmployeeDocument doc = findOrThrow(docId);
        if (!doc.getEmployeeProfileId().equals(profileId)) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Document " + docId + " n'appartient pas au profil " + profileId);
        }
        if (Boolean.TRUE.equals(doc.getIsDeleted())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Document supprimé: id=" + docId);
        }
        if (!"LOCAL".equals(doc.getStorageProvider())) {
            // Reserved for the SharePoint seam: there the controller should redirect to the
            // Graph webUrl rather than stream. Refusing loudly beats streaming a path that
            // does not exist on this disk.
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Stockage non pris en charge pour le téléchargement direct: "
                            + doc.getStorageProvider());
        }

        /*
         * `file_url` is data, and it has been written by three different code paths (upload,
         * staged onboarding upload, and hand-fixed rows). So it is resolved and then checked
         * to be INSIDE the configured storage root: a row holding "..\..\application.yml"
         * must not turn this endpoint into an arbitrary file reader.
         */
        Path root = Paths.get(storagePath).toAbsolutePath().normalize();
        Path target = Paths.get(doc.getFileUrl()).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            log.error("Document {} points outside the storage root: {}", docId, target);
            throw new AppException(ErrorCode.NOT_FOUND, "Fichier introuvable");
        }
        if (!Files.isReadable(target)) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Fichier absent du stockage: " + target.getFileName());
        }

        try {
            Resource resource = new UrlResource(target.toUri());
            String name = doc.getFileName() != null && !doc.getFileName().isBlank()
                    ? doc.getFileName() : target.getFileName().toString();
            return new DownloadPayload(resource, name, probeContentType(target));
        } catch (Exception ex) {
            log.error("Could not open document {}: {}", docId, ex.getMessage());
            throw new AppException(ErrorCode.NOT_FOUND, "Fichier illisible");
        }
    }

    /** Bytes plus what the browser needs to display them. */
    public record DownloadPayload(Resource resource, String fileName, String contentType) {}

    // ── Verify ────────────────────────────────────────────────────────────────

    public DocumentUploadResponseDto verify(Long docId, String status, Authentication auth) {
        EmployeeDocument doc = findOrThrow(docId);

        if (!Set.of("VERIFIED", "REJECTED").contains(status)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Statut invalide — valeurs acceptées: VERIFIED, REJECTED");
        }
        String before = doc.getVerificationStatus();
        doc.setVerificationStatus(status);
        EmployeeDocument saved = documentRepository.save(doc);
        auditService.log(actorId(auth), "VERIFY_DOCUMENT", "EmployeeDocument", docId, before, status);
        return toDto(saved);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    /** Corrects the type, the expiry or the note. The file itself is never replaced. */
    public DocumentUploadResponseDto updateMetadata(Long docId, DocumentMetadataRequest req,
                                                    Authentication auth) {
        EmployeeDocument doc = findOrThrow(docId);
        String before = doc.getDocumentType() + ";exp=" + doc.getExpirationDate();

        if (req.getDocumentType() != null) doc.setDocumentType(normaliseType(req.getDocumentType()));
        // Null means "leave alone" on the type, but expiry and notes must be CLEARABLE, so
        // their own explicit flags say "set this to null" — a PATCH cannot express it otherwise.
        if (Boolean.TRUE.equals(req.getClearExpirationDate())) {
            doc.setExpirationDate(null);
        } else if (req.getExpirationDate() != null) {
            doc.setExpirationDate(req.getExpirationDate());
        }
        if (req.getNotes() != null) doc.setNotes(trimToNull(req.getNotes()));

        EmployeeDocument saved = documentRepository.save(doc);
        auditService.log(actorId(auth), "UPDATE_DOCUMENT", "EmployeeDocument", docId,
                before, saved.getDocumentType() + ";exp=" + saved.getExpirationDate());
        return toDto(saved);
    }

    // ── Soft delete / restore ─────────────────────────────────────────────────

    /**
     * Removes the document from the dossier without destroying it.
     *
     * The row and the file both stay: an HR dossier has to be able to show that a pièce was
     * filed and later withdrawn, and by whom. {@link #restore} undoes it.
     */
    public void softDelete(Long profileId, Long docId, Authentication auth) {
        EmployeeDocument doc = findOrThrow(docId);
        if (!doc.getEmployeeProfileId().equals(profileId)) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Document " + docId + " n'appartient pas au profil " + profileId);
        }
        if (Boolean.TRUE.equals(doc.getIsDeleted())) return;   // idempotent

        doc.setIsDeleted(true);
        doc.setDeletedAt(OffsetDateTime.now());
        doc.setDeletedBy(extractUserId(auth));
        documentRepository.save(doc);

        auditService.log(actorId(auth), "DELETE_DOCUMENT", "EmployeeDocument", docId,
                doc.getDocumentType() + ";" + doc.getFileName(), "isDeleted=true");
    }

    public DocumentUploadResponseDto restore(Long profileId, Long docId, Authentication auth) {
        EmployeeDocument doc = findOrThrow(docId);
        if (!doc.getEmployeeProfileId().equals(profileId)) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Document " + docId + " n'appartient pas au profil " + profileId);
        }
        // CK_emp_docs_deleted ties the flag to the date, so both move together or neither does.
        doc.setIsDeleted(false);
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        EmployeeDocument saved = documentRepository.save(doc);

        auditService.log(actorId(auth), "RESTORE_DOCUMENT", "EmployeeDocument", docId,
                "isDeleted=true", "isDeleted=false");
        return toDto(saved);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private EmployeeDocument findOrThrow(Long docId) {
        return documentRepository.findById(docId).orElseThrow(() ->
                new AppException(ErrorCode.NOT_FOUND, "Document introuvable: id=" + docId));
    }

    /**
     * Accepts a known code, upper-cased and trimmed; anything else is a 422 rather than a
     * silent new "type" that no screen can translate or filter on.
     */
    private String normaliseType(String raw) {
        String type = raw != null ? raw.trim().toUpperCase() : "";
        if (!DOCUMENT_TYPES.contains(type)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Type de document inconnu: " + raw);
        }
        return type;
    }

    private DocumentUploadResponseDto toDto(EmployeeDocument doc) {
        DocumentUploadResponseDto dto = mapper.toDto(doc);
        // Not derivable by MapStruct: the names live in the portal's Users table, which this
        // service reaches through JDBC (no User entity here).
        dto.setUploadedByName(resolveUserName(doc.getUploadedBy()));
        dto.setDeletedByName(resolveUserName(doc.getDeletedBy()));
        return dto;
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(USER_NAME_SQL, String.class, userId);
        } catch (Exception ex) {
            log.debug("Could not resolve name for userId={}: {}", userId, ex.getMessage());
            return null;
        }
    }

    private String probeContentType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null) return probed;
        } catch (IOException ex) {
            log.debug("Could not probe content type for {}: {}", path, ex.getMessage());
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf"))                            return "application/pdf";
        if (name.endsWith(".png"))                            return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))  return "image/jpeg";
        return "application/octet-stream";
    }

    private String contentTypeToExt(String ct) {
        return switch (ct) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg"      -> ".jpg";
            case "image/png"       -> ".png";
            default                -> ".bin";
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
