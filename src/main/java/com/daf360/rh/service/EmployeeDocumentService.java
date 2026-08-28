package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.document.DocumentMetadataRequest;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.EmployeeDocumentMapper;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.document.DocumentTypeService;
import com.daf360.rh.service.sharepoint.DocumentFolderMapping;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.SharePointLocationService;
import com.daf360.rh.service.sharepoint.SharePointPaths;
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
    private final GraphSharePointService     graphSharePointService;
    private final EmployeeFolderResolver     employeeFolderResolver;
    private final DocumentTypeService        documentTypeService;
    private final SharePointLocationService  locationService;

    @Value("${app.storage-path:./uploads/hr}")
    private String storagePath;

    // ── Upload ────────────────────────────────────────────────────────────────

    public DocumentUploadResponseDto upload(Long profileId, MultipartFile file,
                                            String documentType, LocalDate expirationDate,
                                            String notes, Authentication auth) throws IOException {
        // Loaded, not just existence-checked: the SharePoint mirror below needs the pays
        // (which country's tree) and the user id (whose folder).
        EmployeeProfile profile = profileRepository.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId));

        String type = normaliseType(profile.getPaysId(), documentType);

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

        // Read once, use twice: the multipart stream can only be consumed once, and the
        // SharePoint mirror below needs the same bytes the local file gets. (This replaced
        // file.transferTo(dest), same change the profile-photo mirror had to make.) The
        // 10 MB cap above is what makes holding them in memory acceptable.
        byte[] bytes = file.getBytes();

        // Store to: <storagePath>/<profileId>/<uuid><ext>
        Path dir = Paths.get(storagePath, String.valueOf(profileId));
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + ext;
        Path dest = dir.resolve(filename);
        Files.write(dest, bytes);

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

        // Best-effort mirror. Deliberately after save(): the row and the local file are the
        // record, SharePoint is a copy, and mirrorToSharePoint() never throws — an upload
        // must not fail because Graph is down or unconfigured.
        mirrorToSharePoint(saved, profile, bytes);

        auditService.log(actorId(auth), "UPLOAD_DOCUMENT", "EmployeeDocument", saved.getId(),
                null, type);
        return toDto(saved);
    }

    /**
     * The document types this profile's country accepts.
     *
     * <p>Resolved from the profile rather than from a caller-supplied country, so the dropdown
     * on a page can never offer a type that would then be rejected by
     * {@link #normaliseType} — the two now read the same per-country vocabulary.
     */
    @Transactional(readOnly = true)
    public List<DocumentTypeService.DocumentType> typesForProfile(Long profileId) {
        Long paysId = profileRepository.findById(profileId)
                .map(EmployeeProfile::getPaysId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Profil introuvable: id=" + profileId));
        return documentTypeService.forPays(paysId);
    }

    // ── What is really in SharePoint ──────────────────────────────────────────

    /**
     * One file found in the employee's SharePoint folder for a type.
     *
     * @param filedByApp true when the name carries the {@code {docId}_} prefix this service
     *                   writes, i.e. the same file the DB already lists. The tab uses it to show
     *                   one row instead of two.
     * @param docId      the id parsed out of that prefix, so the caller can pair the two lists
     *                   without matching on names
     */
    public record RemoteDocument(String name, String lastModified, Long sizeBytes, String webUrl,
                                 boolean filedByApp, Long docId) {}

    /**
     * Lists the employee's SharePoint folder for one document type.
     *
     * <p>This is the half of the dossier the app never showed: a document existed only if it was
     * uploaded THROUGH the app, while HR files most paperwork straight into SharePoint — exactly
     * as they did with the profile photos. The tab was therefore empty for the majority of real
     * documents.
     *
     * <p>Per type, not per employee: listing every configured folder on tab open is one Graph
     * round trip per type per profile view, which is how you earn a 429 on a directory page.
     * The caller expands a section and pays for that section only.
     *
     * <p>Empty — never an exception — when Graph is unconfigured, the type has no path, the
     * folder does not exist, or the listing fails. Same posture as the rest of this package: the
     * local rows remain visible regardless.
     */
    @Transactional(readOnly = true)
    public List<RemoteDocument> listRemote(Long profileId, String documentType) {
        EmployeeProfile profile = profileRepository.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId));
        String folder = remoteFolder(profile, documentType);
        if (folder == null) return List.of();

        return graphSharePointService.listFiles(folder).stream()
                .map(f -> {
                    Long docId = parseDocIdPrefix(f.name());
                    return new RemoteDocument(f.name(), f.lastModified(), f.sizeBytes(),
                            f.webUrl(), docId != null, docId);
                })
                .toList();
    }

    /**
     * Streams one file out of the employee's SharePoint folder.
     *
     * <p><b>The folder is derived here, from {@code (profileId, documentType)} — never taken from
     * the caller.</b> This endpoint reaches a site holding every employee's contracts, ID scans
     * and payslips, so a caller-supplied path would be a directory-traversal hole over exactly
     * the data that must not leak. The only thing the caller names is a file, and a name
     * containing a path separator is rejected rather than cleaned: a name that needs cleaning is
     * not the name the caller meant.
     *
     * @throws AppException NOT_FOUND when the type has no folder, the name is unsafe, or
     *                      SharePoint has no such file — the three are deliberately
     *                      indistinguishable to the caller
     */
    @Transactional(readOnly = true)
    public DownloadPayload downloadRemote(Long profileId, String documentType, String fileName) {
        EmployeeProfile profile = profileRepository.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND, "Profil introuvable: id=" + profileId));

        if (fileName == null || fileName.isBlank()
                || !fileName.equals(SharePointPaths.safeFileName(fileName, ""))) {
            throw new AppException(ErrorCode.NOT_FOUND, "Nom de fichier invalide");
        }
        String folder = remoteFolder(profile, documentType);
        if (folder == null) {
            throw new AppException(ErrorCode.NOT_FOUND,
                    "Aucun dossier SharePoint pour le type " + documentType);
        }

        byte[] content = graphSharePointService.downloadFile(folder + "/" + fileName).orElseThrow(
                () -> new AppException(ErrorCode.NOT_FOUND, "Fichier introuvable sur SharePoint"));

        return new DownloadPayload(
                new org.springframework.core.io.ByteArrayResource(content),
                fileName,
                contentTypeFor(fileName));
    }

    /**
     * The employee's folder for a type, with the placeholder substituted, or null when it cannot
     * be determined (unknown type for this country, no configured path, unresolvable employee
     * folder). Shared by the listing and the download so the two can never disagree about which
     * folder they are talking about.
     */
    private String remoteFolder(EmployeeProfile profile, String documentType) {
        String type = documentTypeService.validate(profile.getPaysId(), documentType).orElse(null);
        if (type == null) return null;

        EmployeeFolderResolver.EmployeeFolder folder =
                employeeFolderResolver.resolve(profile.getPaysId(), profile.getUserId());
        if (folder == null) return null;

        return templateFor(profile.getPaysId(), type, folder)
                .replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, folder.employeeFolder());
    }

    /**
     * The document id in a {@code 42_contrat.pdf} name, or null.
     *
     * <p>That prefix is written by {@link #locate}, and it is the only reliable way to tell "the
     * file the app uploaded" from "the file HR dropped in": matching on the original name fails
     * as soon as two employees both file {@code contrat.pdf}, which is why the prefix exists.
     */
    private Long parseDocIdPrefix(String name) {
        if (name == null) return null;
        int underscore = name.indexOf('_');
        if (underscore <= 0) return null;
        try {
            return Long.parseLong(name.substring(0, underscore));
        } catch (NumberFormatException notAnId) {
            return null;
        }
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
        EmployeeDocument saved = documentRepository.save(doc);

        // Mirror this one too — it is how the SIGNED CONTRACT arrives (the onboarding wizard
        // uploads it before the profile exists), which is exactly the document HR most wants
        // in SharePoint. Bytes come off disk here rather than from a multipart stream; a
        // missing or unreadable file just skips the mirror, same as any other failure.
        try {
            profileRepository.findById(profileId).ifPresent(profile ->
                    mirrorToSharePoint(saved, profile, readAllBytesOrNull(fileUrl)));
        } catch (Exception e) {
            log.warn("Echec du mirroring SharePoint du document stage {}: {}",
                    saved.getId(), e.getMessage());
        }
    }

    /** Bytes of a stored file, or null when it cannot be read (the mirror then skips). */
    private byte[] readAllBytesOrNull(String fileUrl) {
        try {
            return Files.readAllBytes(Paths.get(fileUrl));
        } catch (Exception e) {
            log.debug("Document stage {} illisible pour le mirroring: {}", fileUrl, e.getMessage());
            return null;
        }
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
            // Cache miss, not necessarily a lost document: pull the SharePoint copy back and
            // re-cache it. Only reached when the local file is genuinely gone (fresh
            // container, cleared disk), never on the normal path.
            Path restored = fetchFromSharePoint(doc, target);
            if (restored == null) {
                throw new AppException(ErrorCode.NOT_FOUND,
                        "Fichier absent du stockage: " + target.getFileName());
            }
            target = restored;
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

    // ── SharePoint mirroring ──────────────────────────────────────────────────

    /**
     * Copies an uploaded document into the employee's SharePoint folder, under the subfolder
     * {@link DocumentFolderMapping} picks for its type.
     *
     * <p>Never throws. Every reason to skip — SharePoint unconfigured, country with no
     * location, ambiguous employee name, network/auth/permission failure — leaves the row
     * exactly as it was saved: {@code storage_provider = LOCAL}, served from disk. That is
     * the same philosophy the photo mirror and the generated-PDF upload already follow, and
     * it is why enabling the {@code MS_GRAPH_*} variables cannot break document upload.
     *
     * <p>The remote file name is {@code <id>_<original name>}: deterministic, so
     * {@link #fetchFromSharePoint} can rebuild the exact path years later without storing
     * it, and unique, so two uploads of {@code contrat.pdf} do not overwrite each other the
     * way a plain original name would (Graph's PUT replaces by default).
     */
    private void mirrorToSharePoint(EmployeeDocument doc, EmployeeProfile profile, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        try {
            DocLocation loc = locate(doc, profile);
            if (loc == null) return;
            graphSharePointService
                    .uploadDocument(loc.locationTemplate(), loc.employeeFolder(), loc.fileName(), bytes)
                    .ifPresent(url -> log.info("Document {} ({}) copie sur SharePoint: {}",
                            doc.getId(), doc.getDocumentType(), url));
        } catch (Exception e) {
            log.warn("Echec du mirroring SharePoint du document {} (copie locale conservee): {}",
                    doc.getId(), e.getMessage());
        }
    }

    /**
     * Where this document belongs on SharePoint, or null when it belongs nowhere (country
     * not wired up, ambiguous employee name).
     *
     * <p>{@code locationTemplate} keeps its {@code {employeeFolder}} placeholder, because
     * that is the contract {@code GraphSharePointService.uploadDocument} expects; {@link
     * #fullPath()} is the substituted form the download side needs.
     *
     * <p>Recomputed from the same three inputs at upload and at download — employee folder,
     * type subfolder, document id — rather than persisted. That keeps this feature free of a
     * schema change (rh-service has no Flyway, so every added column is a manual step that
     * can be missed on one server and silently disable the feature there), and lets a
     * corrected {@link DocumentFolderMapping} apply to new uploads with no data migration.
     */
    private record DocLocation(String locationTemplate, String employeeFolder, String fileName) {
        String fullPath() {
            return SharePointPaths.join(
                    locationTemplate.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, employeeFolder),
                    fileName);
        }
    }

    private DocLocation locate(EmployeeDocument doc, EmployeeProfile profile) {
        EmployeeFolderResolver.EmployeeFolder folder =
                employeeFolderResolver.resolve(profile.getPaysId(), profile.getUserId());
        if (folder == null) return null;

        String safeName = SharePointPaths.safeFileName(doc.getFileName(), "document-" + doc.getId());
        return new DocLocation(
                templateFor(profile.getPaysId(), doc.getDocumentType(), folder),
                folder.employeeFolder(),
                doc.getId() + "_" + safeName);
    }

    /**
     * The path template for one document type, from {@code sharepoint_locations} (V87 seeds a
     * row per country and type) or, failing that, from the hardcoded map.
     *
     * <p>The fallback is not defensive padding — it is the only thing that makes this
     * deployable. rh-service has no Flyway, so V87 is applied by hand, and on a server that
     * missed it every upload would otherwise stop mirroring at once. It also keeps DOWNLOAD
     * working for documents uploaded before the migration: {@code fetchFromSharePoint}
     * recomputes the path from this same method, so if the two disagreed about where a file
     * went, an old document would become unreachable the day V87 was applied. They agree
     * because V87 seeds exactly what the map produces.
     *
     * <p>Remove the fallback once every environment is confirmed migrated, and delete
     * {@code DocumentFolderMapping} with it.
     */
    private String templateFor(Long paysId, String documentType,
                               EmployeeFolderResolver.EmployeeFolder folder) {
        return locationService.templateForCode(paysId, documentType)
                .orElseGet(() -> SharePointPaths.join(
                        folder.locationTemplate(),
                        DocumentFolderMapping.subfolderFor(documentType)));
    }

    /**
     * Last resort when the local file is gone: pull the SharePoint copy back and re-cache it
     * on disk, so the next download is local again.
     *
     * <p>This is what makes the local directory a cache rather than the only copy. It matters
     * concretely: with no volume mounted for {@code STORAGE_PATH}, every container recreate
     * wipes it, and before this the documents were simply lost.
     *
     * @return the re-cached file, or null if SharePoint has nothing either
     */
    private Path fetchFromSharePoint(EmployeeDocument doc, Path expected) {
        EmployeeProfile profile = profileRepository.findById(doc.getEmployeeProfileId()).orElse(null);
        if (profile == null) return null;

        DocLocation loc = locate(doc, profile);
        if (loc == null) return null;

        byte[] content = graphSharePointService.downloadFile(loc.fullPath()).orElse(null);
        if (content == null) return null;

        try {
            Files.createDirectories(expected.getParent());
            Files.write(expected, content);
            log.info("Document {} restaure depuis SharePoint vers {}", doc.getId(), expected);
            return expected;
        } catch (IOException e) {
            // The bytes are in hand; failing to cache them is not a reason to fail the
            // download, so write them to a temp file and serve that instead.
            log.warn("Impossible de remettre en cache le document {} sur {}: {}",
                    doc.getId(), expected, e.getMessage());
            try {
                Path tmp = Files.createTempFile("daf360-doc-" + doc.getId() + "-", null);
                Files.write(tmp, content);
                return tmp;
            } catch (IOException fatal) {
                log.error("Document {} illisible malgre la copie SharePoint: {}",
                        doc.getId(), fatal.getMessage());
                return null;
            }
        }
    }

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

        if (req.getDocumentType() != null) {
            // The type vocabulary is per-country, so a correction has to be validated against
            // the same country the document belongs to -- not against a global list.
            Long paysId = profileRepository.findById(doc.getEmployeeProfileId())
                    .map(EmployeeProfile::getPaysId).orElse(null);
            doc.setDocumentType(normaliseType(paysId, req.getDocumentType()));
        }
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
     * Accepts a code this COUNTRY declares, upper-cased and trimmed; anything else is a 422
     * rather than a silent new "type" that no screen can translate or filter on.
     *
     * <p>The vocabulary moved to {@code document_types} (V87) and is per-country, so the same
     * code can be valid for Tunisia and rejected for Egypt — which is the point: CNSS has no
     * Egyptian equivalent. {@link DocumentTypeService} falls back to {@link #DOCUMENT_TYPES}
     * for a country with no rows, so a server without V87 behaves exactly as before.
     */
    private String normaliseType(Long paysId, String raw) {
        return documentTypeService.validate(paysId, raw)
                .orElseThrow(() -> new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Type de document inconnu: " + raw));
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
        return contentTypeFor(path.getFileName().toString());
    }

    /**
     * Content type from a file NAME alone.
     *
     * <p>Split out of {@link #probeContentType} for the SharePoint download, where there is no
     * local file to probe — {@code Files.probeContentType} needs a path on disk, and the remote
     * bytes never land on one. Same three types the upload whitelist accepts.
     */
    private String contentTypeFor(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
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
