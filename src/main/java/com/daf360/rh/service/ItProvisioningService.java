package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItAsset;
import com.daf360.rh.domain.ItAssetType;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.dto.provisioning.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.service.pdf.PdfDocumentService;
import java.util.Map;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.ItAssetRepository;
import com.daf360.rh.repository.ItAssetTypeRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ItProvisioningService {

    private static final List<ItProvisioningStatus> OPEN_STATUSES = List.of(
            ItProvisioningStatus.PENDING,
            ItProvisioningStatus.IN_PROGRESS,
            ItProvisioningStatus.EMAIL_CREATED);

    private static final List<ItProvisioningStatus> ALL_STATUSES = List.of(
            ItProvisioningStatus.PENDING,
            ItProvisioningStatus.IN_PROGRESS,
            ItProvisioningStatus.EMAIL_CREATED,
            ItProvisioningStatus.COMPLETED);

    private static final String CHECK_USERNAME_SQL =
        "SELECT COUNT(*) FROM [dbo].[Users] WHERE username = ?";

    private static final String COLLABORATEUR_ROLE_SQL =
        "SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Collaborateur' AND (deleted = 0 OR deleted IS NULL)";

    private static final String INSERT_USER_SQL =
        "INSERT INTO [dbo].[Users] (fullName, username, email, azure_upn, pays_id, isActive, role_id, created_at) " +
        "VALUES (?, ?, ?, ?, ?, 1, ?, SYSDATETIMEOFFSET())";

    private static final String UPDATE_MATRICULE_SQL =
        "UPDATE [dbo].[Users] SET employee_id = ? WHERE id = ?";

    private static final String USERS_WITH_PERMISSION_SQL =
        "SELECT DISTINCT u.id FROM [dbo].[Users] u " +
        "JOIN [dbo].[Roles] r       ON r.id    = u.role_id " +
        "JOIN [dbo].[RolePermissions] rp ON rp.role_id = r.id " +
        "WHERE rp.permission = ? AND u.pays_id = ? AND (u.isActive = 1 OR u.isActive IS NULL)";

    private static final String INSERT_NOTIFICATION_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, 'HR', ?, ?, 0, SYSDATETIMEOFFSET())";

    private final ItProvisioningRepository itProvRepo;
    private final CandidateRepository      candidateRepo;
    private final ItAssetRepository        assetRepo;
    private final ItAssetTypeRepository    assetTypeRepo;
    private final EmployeeIdGeneratorService idGeneratorService;
    private final AuditService             auditService;
    private final JdbcTemplate             jdbc;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;
    private final PdfDocumentService       pdfDocumentService;
    private final com.daf360.rh.security.TenantService tenantService;

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProvisioningListItem> getPendingList() {
        Long paysId = tenantService.getEffectivePaysId();
        List<ItProvisioning> provs = paysId != null
                ? itProvRepo.findByStatusInAndCandidatePaysId(OPEN_STATUSES, paysId)
                : itProvRepo.findByStatusIn(OPEN_STATUSES);
        if (provs.isEmpty()) return List.of();

        Set<Long> candidateIds = provs.stream()
                .map(ItProvisioning::getCandidateId).collect(Collectors.toSet());
        Map<Long, Candidate> candidateMap = candidateRepo.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(Candidate::getId, c -> c));

        // Batch-load assets to avoid N+1
        Set<Long> provIds = provs.stream().map(ItProvisioning::getId).collect(Collectors.toSet());
        Map<Long, List<ItAsset>> assetsByProv = new HashMap<>();
        for (Long provId : provIds) {
            assetsByProv.put(provId, assetRepo.findByProvisioningId(provId));
        }

        return provs.stream()
                .sorted(Comparator.comparing(p -> {
                    Candidate c = candidateMap.get(p.getCandidateId());
                    return c != null && c.getAcceptedAt() != null
                            ? c.getAcceptedAt()
                            : OffsetDateTime.MIN;
                }))
                .map(p -> toListItem(p, candidateMap.get(p.getCandidateId()),
                        assetsByProv.getOrDefault(p.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProvisioningListItem> getAllList() {
        Long paysId = tenantService.getEffectivePaysId();
        List<ItProvisioning> provs = paysId != null
                ? itProvRepo.findByStatusInAndCandidatePaysId(ALL_STATUSES, paysId)
                : itProvRepo.findByStatusIn(ALL_STATUSES);
        if (provs.isEmpty()) return List.of();

        Set<Long> candidateIds = provs.stream()
                .map(ItProvisioning::getCandidateId).collect(Collectors.toSet());
        Map<Long, Candidate> candidateMap = candidateRepo.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(Candidate::getId, c -> c));

        Set<Long> provIds = provs.stream().map(ItProvisioning::getId).collect(Collectors.toSet());
        Map<Long, List<ItAsset>> assetsByProv = new HashMap<>();
        for (Long provId : provIds) {
            assetsByProv.put(provId, assetRepo.findByProvisioningId(provId));
        }

        return provs.stream()
                .sorted(Comparator.comparing(p -> {
                    Candidate c = candidateMap.get(p.getCandidateId());
                    return c != null && c.getAcceptedAt() != null
                            ? c.getAcceptedAt()
                            : OffsetDateTime.MIN;
                }))
                .map(p -> toListItem(p, candidateMap.get(p.getCandidateId()),
                        assetsByProv.getOrDefault(p.getId(), List.of())))
                .collect(Collectors.toList());
    }

    public ProvisioningResponse getProvisioning(Long id) {
        ItProvisioning prov = findOrThrow(id);
        Candidate candidate = candidateRepo.findById(prov.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
        List<ItAsset> saved = assetRepo.findByProvisioningId(id);
        List<ItAsset> assets = mergeWithAllTypes(prov, saved);
        return toResponse(prov, candidate, assets);
    }

    /** Returns saved assets merged with all active asset types. Types with no saved row appear as
     *  transient (unsaved) empty slots so the form always shows the full hardware list. */
    private List<ItAsset> mergeWithAllTypes(ItProvisioning prov, List<ItAsset> saved) {
        List<ItAssetType> allTypes = assetTypeRepo.findAllByIsActiveTrueOrderBySortOrderAsc();
        Map<String, ItAsset> savedByCode = saved.stream()
                .filter(a -> a.getAssetType() != null)
                .collect(Collectors.toMap(a -> a.getAssetType().getCode(), a -> a));
        return allTypes.stream()
                .map(type -> savedByCode.containsKey(type.getCode())
                        ? savedByCode.get(type.getCode())
                        : ItAsset.builder().provisioning(prov).assetType(type).provided(false).build())
                .collect(Collectors.toList());
    }

    // ── Updates ───────────────────────────────────────────────────────────────

    public ProvisioningResponse updateProvisioning(Long id, UpdateProvisioningRequest dto,
                                                   Long itManagerId) {
        ItProvisioning prov = findOrThrow(id);
        String before = "status=" + prov.getStatus();

        // Apply license / AD / notes fields (PATCH — non-null only)
        if (dto.getLicenseOffice365()   != null) prov.setLicenseOffice365(dto.getLicenseOffice365());
        if (dto.getLicenseAutocad()     != null) prov.setLicenseAutocad(dto.getLicenseAutocad());
        if (dto.getLicenseRevit()       != null) prov.setLicenseRevit(dto.getLicenseRevit());
        if (dto.getLicenseAutodesk()    != null) prov.setLicenseAutodesk(dto.getLicenseAutodesk());
        if (dto.getLicenseKaspersky()   != null) prov.setLicenseKaspersky(dto.getLicenseKaspersky());
        if (dto.getLicenseOther()       != null) prov.setLicenseOther(dto.getLicenseOther());
        if (dto.getAdAccountCreated()   != null) prov.setAdAccountCreated(dto.getAdAccountCreated());
        if (dto.getAdProfileType()      != null) prov.setAdProfileType(dto.getAdProfileType());
        if (dto.getAdAccountCreatedAt() != null) prov.setAdAccountCreatedAt(dto.getAdAccountCreatedAt());
        if (dto.getNotes()              != null) prov.setNotes(dto.getNotes());

        // Apply hardware asset updates
        List<ItAsset> updatedAssets = new ArrayList<>();
        if (dto.getAssets() != null && !dto.getAssets().isEmpty()) {
            // Load existing assets keyed by type code for efficient lookup
            List<ItAsset> existing = assetRepo.findByProvisioningId(prov.getId());
            Map<String, ItAsset> existingByCode = existing.stream()
                    .filter(a -> a.getAssetType() != null)
                    .collect(Collectors.toMap(a -> a.getAssetType().getCode(), a -> a,
                             (a, b) -> a)); // keep first on duplicate

            for (ItAssetUpdateRequest assetReq : dto.getAssets()) {
                if (assetReq.getAssetTypeCode() == null) continue;

                ItAsset asset = existingByCode.get(assetReq.getAssetTypeCode());
                if (asset == null) {
                    // Create new asset record
                    ItAssetType assetType = assetTypeRepo.findByCode(assetReq.getAssetTypeCode())
                            .orElseGet(() -> {
                                // Auto-create unknown type so provisioning never hard-fails
                                log.warn("Unknown asset type code '{}' — creating on the fly",
                                        assetReq.getAssetTypeCode());
                                return assetTypeRepo.save(ItAssetType.builder()
                                        .code(assetReq.getAssetTypeCode())
                                        .labelFr(assetReq.getAssetTypeCode())
                                        .labelEn(assetReq.getAssetTypeCode())
                                        .build());
                            });
                    asset = ItAsset.builder()
                            .provisioning(prov)
                            .assetType(assetType)
                            .build();
                }
                if (assetReq.getProvided()      != null) asset.setProvided(assetReq.getProvided());
                if (assetReq.getSerialNumber()  != null) asset.setSerialNumber(assetReq.getSerialNumber());
                if (assetReq.getBrandModel()    != null) asset.setBrandModel(assetReq.getBrandModel());
                if (assetReq.getAssetTag()      != null) asset.setAssetTag(assetReq.getAssetTag());
                if (assetReq.getStatus()        != null) asset.setStatus(assetReq.getStatus());

                boolean empty = !Boolean.TRUE.equals(asset.getProvided())
                        && (asset.getSerialNumber() == null || asset.getSerialNumber().isBlank())
                        && (asset.getBrandModel()   == null || asset.getBrandModel().isBlank())
                        && (asset.getAssetTag()     == null || asset.getAssetTag().isBlank());
                if (empty) {
                    if (asset.getId() != null) assetRepo.delete(asset);
                } else {
                    updatedAssets.add(assetRepo.save(asset));
                }
            }
        } else {
            updatedAssets = assetRepo.findByProvisioningId(prov.getId());
        }

        // First touch on a PENDING task → move to IN_PROGRESS
        if (prov.getStatus() == ItProvisioningStatus.PENDING) {
            prov.setStatus(ItProvisioningStatus.IN_PROGRESS);
        }

        prov.setUpdatedAt(OffsetDateTime.now());
        prov = itProvRepo.save(prov);

        auditService.log(itManagerId != null ? itManagerId.toString() : "SYSTEM", "UPDATE_IT_PROVISIONING", "IT_PROVISIONING",
                prov.getId(), before, "status=" + prov.getStatus());

        Candidate candidate = candidateRepo.findById(prov.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
        return toResponse(prov, candidate, mergeWithAllTypes(prov, updatedAssets));
    }

    public ProvisioningResponse submitEmail(Long id, String ms365Email, Long itManagerId) {
        ItProvisioning prov = findOrThrow(id);
        Candidate candidate = candidateRepo.findById(prov.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));

        Integer usernameCount = jdbc.queryForObject(CHECK_USERNAME_SQL, Integer.class, ms365Email);
        if (usernameCount != null && usernameCount > 0) {
            throw new AppException(ErrorCode.IT_EMAIL_ALREADY_IN_USE,
                    "L'email " + ms365Email + " est déjà utilisé comme identifiant système");
        }

        Long collaborateurRoleId = jdbc.queryForObject(COLLABORATEUR_ROLE_SQL, Long.class);
        if (collaborateurRoleId == null) {
            throw new AppException(ErrorCode.IT_COLLABORATEUR_ROLE_NOT_FOUND);
        }

        String fullName = candidate.getFirstName() + " " + candidate.getLastName();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_USER_SQL,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fullName);
            ps.setString(2, ms365Email);
            ps.setString(3, ms365Email);
            ps.setString(4, ms365Email);
            ps.setLong(5, candidate.getPaysId());
            ps.setLong(6, collaborateurRoleId);
            return ps;
        }, keyHolder);
        Long newUserId = Objects.requireNonNull(keyHolder.getKey()).longValue();

        // Generate matricule AFTER INSERT — userId is needed for uniqueness
        // Format: [3 letters lastName][3 letters firstName][userId]
        // Example: Dupont Pierre, id=125 → DUPPIE125
        String matricule = idGeneratorService.generate(
                candidate.getLastName(), candidate.getFirstName(), newUserId);
        jdbc.update(UPDATE_MATRICULE_SQL, matricule, newUserId);
        log.info("Created User id={} matricule={} email={}", newUserId, matricule, ms365Email);

        prov.setMs365Email(ms365Email);
        prov.setMs365EmailCreatedAt(OffsetDateTime.now());
        prov.setUserId(newUserId);
        prov.setStatus(ItProvisioningStatus.EMAIL_CREATED);
        prov.setUpdatedAt(OffsetDateTime.now());
        prov = itProvRepo.save(prov);

        candidate.setStatus(CandidateStatus.EMAIL_RECEIVED);
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepo.save(candidate);

        notificationRoutingService.resolveAndDispatch(
            RoutingContext.builder()
                .eventCode("IT_EMAIL_SUBMITTED")
                .paysId(candidate.getPaysId())
                .templateVars(Map.of(
                    "candidateName", candidate.getFirstName() + " " + candidate.getLastName(),
                    "firstName", candidate.getFirstName(),
                    "ms365Email", ms365Email
                ))
                .build()
        );

        auditService.log(itManagerId != null ? itManagerId.toString() : "SYSTEM", "SUBMIT_MS365_EMAIL", "IT_PROVISIONING",
                prov.getId(), "status=IN_PROGRESS",
                "status=EMAIL_CREATED; email=" + ms365Email + "; userId=" + newUserId);

        List<ItAsset> assets = assetRepo.findByProvisioningId(prov.getId());
        return toResponse(prov, candidate, assets);
    }

    public ProvisioningResponse completeProvisioning(Long id, Long itManagerId) {
        ItProvisioning prov = findOrThrow(id);

        if (prov.getMs365Email() == null || prov.getMs365Email().isBlank()) {
            throw new AppException(ErrorCode.IT_EMAIL_REQUIRED);
        }
        if (!Boolean.TRUE.equals(prov.getAdAccountCreated())) {
            throw new AppException(ErrorCode.IT_AD_ACCOUNT_REQUIRED);
        }

        prov.setStatus(ItProvisioningStatus.COMPLETED);
        prov.setCompletedBy(itManagerId);
        prov.setCompletedAt(OffsetDateTime.now());
        prov.setUpdatedAt(OffsetDateTime.now());
        prov = itProvRepo.save(prov);

        auditService.log(itManagerId != null ? itManagerId.toString() : "SYSTEM", "COMPLETE_IT_PROVISIONING", "IT_PROVISIONING",
                prov.getId(), "status=EMAIL_CREATED", "status=COMPLETED");

        try {
            pdfDocumentService.generateDechargePdf(
                    prov.getCandidateId(), prov.getId(), "IT_COMPLETE", itManagerId);
        } catch (Exception ex) {
            log.warn("Décharge PDF generation failed for provisioning {}: {}", prov.getId(), ex.getMessage());
        }

        Candidate candidate = candidateRepo.findById(prov.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
        List<ItAsset> assets = assetRepo.findByProvisioningId(prov.getId());
        return toResponse(prov, candidate, assets);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ItProvisioning findOrThrow(Long id) {
        return itProvRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.IT_PROVISIONING_NOT_FOUND));
    }

    private ItAssetDto toAssetDto(ItAsset a) {
        return ItAssetDto.builder()
                .id(a.getId())
                .assetTypeCode(a.getAssetType() != null ? a.getAssetType().getCode() : null)
                .assetTypeLabelFr(a.getAssetType() != null ? a.getAssetType().getLabelFr() : null)
                .provided(a.getProvided())
                .serialNumber(a.getSerialNumber())
                .brandModel(a.getBrandModel())
                .assetTag(a.getAssetTag())
                .status(a.getStatus())
                .build();
    }

    private ProvisioningListItem toListItem(ItProvisioning p, Candidate c, List<ItAsset> assets) {
        return ProvisioningListItem.builder()
                .id(p.getId())
                .candidateId(p.getCandidateId())
                .candidateFullName(c != null ? c.getFirstName() + " " + c.getLastName() : null)
                .appliedPosition(c != null ? c.getAppliedPosition() : null)
                .paysId(c != null ? c.getPaysId() : null)
                .expectedStartDate(c != null ? c.getExpectedStartDate() : null)
                .candidateAcceptedAt(c != null ? c.getAcceptedAt() : null)
                .status(p.getStatus())
                .ms365Email(p.getMs365Email())
                .assetsProvided((int) assets.stream().filter(a -> Boolean.TRUE.equals(a.getProvided())).count())
                .licenseOffice365(p.getLicenseOffice365())
                .licenseAutocad(p.getLicenseAutocad())
                .licenseRevit(p.getLicenseRevit())
                .licenseAutodesk(p.getLicenseAutodesk())
                .licenseKaspersky(p.getLicenseKaspersky())
                .licenseOther(p.getLicenseOther())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private ProvisioningResponse toResponse(ItProvisioning p, Candidate c, List<ItAsset> assets) {
        return ProvisioningResponse.builder()
                .id(p.getId())
                .candidateId(p.getCandidateId())
                .userId(p.getUserId())
                .status(p.getStatus())
                .ms365Email(p.getMs365Email())
                .ms365EmailCreatedAt(p.getMs365EmailCreatedAt())
                .assets(assets.stream().map(this::toAssetDto).collect(Collectors.toList()))
                .hardwareNotes(p.getHardwareNotes())
                .licenseOffice365(p.getLicenseOffice365())
                .licenseAutocad(p.getLicenseAutocad())
                .licenseRevit(p.getLicenseRevit())
                .licenseAutodesk(p.getLicenseAutodesk())
                .licenseKaspersky(p.getLicenseKaspersky())
                .licenseOther(p.getLicenseOther())
                .adAccountCreated(p.getAdAccountCreated())
                .adProfileType(p.getAdProfileType())
                .adAccountCreatedAt(p.getAdAccountCreatedAt())
                .completedBy(p.getCompletedBy())
                .completedAt(p.getCompletedAt())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .candidateFullName(c != null ? c.getFirstName() + " " + c.getLastName() : null)
                .appliedPosition(c != null ? c.getAppliedPosition() : null)
                .paysId(c != null ? c.getPaysId() : null)
                .expectedStartDate(c != null ? c.getExpectedStartDate() : null)
                .candidateAcceptedAt(c != null ? c.getAcceptedAt() : null)
                .build();
    }

    @SuppressWarnings("unused")
    private void notifyUsersWithPermission(String permission, Long paysId,
                                           String title, String message) {
        try {
            List<Long> userIds = jdbc.queryForList(
                    USERS_WITH_PERMISSION_SQL, Long.class, permission, paysId);
            for (Long uid : userIds) {
                jdbc.update(INSERT_NOTIFICATION_SQL, uid, title, message);
            }
        } catch (Exception ex) {
            log.error("Notification failed for permission={} pays={}: {}",
                    permission, paysId, ex.getMessage());
        }
    }
}
