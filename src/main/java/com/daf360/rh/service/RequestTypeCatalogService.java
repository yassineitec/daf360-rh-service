package com.daf360.rh.service;

import com.daf360.rh.domain.RequestTypeCatalog;
import com.daf360.rh.domain.enums.RequestCategory;
import com.daf360.rh.dto.requests.RequestTypeCreateDto;
import com.daf360.rh.dto.requests.RequestTypeResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.RequestTypeCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages request_type_catalog — Admin only.
 * Seeds 15 default types per pays on first run (when catalog is empty).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RequestTypeCatalogService {

    private final RequestTypeCatalogRepository typeRepo;
    private final AuditService                 auditService;
    private final JdbcTemplate                 jdbcTemplate;

    // ── Seed ──────────────────────────────────────────────────────────────────

    /**
     * Idempotent seed: inserts 15 default types for each pays that has no entries yet.
     * Called once from DataInitializer or on demand.
     */
    public void seedDefaults() {
        List<Long> paysIds = jdbcTemplate.queryForList(
                "SELECT id FROM [dbo].[pays] WHERE deleted = 0 OR deleted IS NULL", Long.class);

        for (Long paysId : paysIds) {
            if (typeRepo.countByPaysId(paysId) > 0) continue;
            DEFAULT_TYPES.forEach(seed -> {
                if (!typeRepo.existsByPaysIdAndTypeCode(paysId, seed.typeCode)) {
                    RequestTypeCatalog entity = RequestTypeCatalog.builder()
                            .paysId(paysId)
                            .typeCode(seed.typeCode)
                            .displayNameFr(seed.nameFr)
                            .displayNameEn(seed.nameEn)
                            .description(seed.description)
                            .category(seed.category)
                            .approvalLevel(seed.approvalLevel)
                            .defaultSlaDays(seed.slaDays)
                            .isActive(true)
                            .createdAt(LocalDateTime.now())
                            .build();
                    typeRepo.save(entity);
                }
            });
            log.info("Seeded {} default request types for paysId={}", DEFAULT_TYPES.size(), paysId);
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RequestTypeResponseDto> list(Long paysId) {
        return typeRepo.findByPaysIdAndIsActiveTrue(paysId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RequestTypeResponseDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    public RequestTypeResponseDto create(RequestTypeCreateDto dto, Authentication auth) {
        if (typeRepo.existsByPaysIdAndTypeCode(dto.getPaysId(), dto.getTypeCode())) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Type " + dto.getTypeCode() + " exists for paysId=" + dto.getPaysId());
        }
        RequestTypeCatalog entity = RequestTypeCatalog.builder()
                .paysId(dto.getPaysId())
                .typeCode(dto.getTypeCode().toUpperCase())
                .displayNameFr(dto.getDisplayNameFr())
                .displayNameEn(dto.getDisplayNameEn())
                .description(dto.getDescription())
                .category(dto.getCategory())
                .approvalLevel(dto.getApprovalLevel() != null ? dto.getApprovalLevel() : "L1")
                .defaultSlaDays(dto.getDefaultSlaDays() != null ? dto.getDefaultSlaDays() : 2)
                .documentTemplateUrl(dto.getDocumentTemplateUrl())
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();
        RequestTypeCatalog saved = typeRepo.save(entity);
        auditService.log(actorId(auth), "CREATE_REQUEST_TYPE", "RequestTypeCatalog", saved.getId(),
                null, saved.getTypeCode());
        return toDto(saved);
    }

    public RequestTypeResponseDto update(Long id, RequestTypeCreateDto dto, Authentication auth) {
        RequestTypeCatalog entity = findOrThrow(id);
        if (dto.getDisplayNameFr() != null) entity.setDisplayNameFr(dto.getDisplayNameFr());
        if (dto.getDisplayNameEn() != null) entity.setDisplayNameEn(dto.getDisplayNameEn());
        if (dto.getDescription() != null)   entity.setDescription(dto.getDescription());
        if (dto.getDefaultSlaDays() != null) entity.setDefaultSlaDays(dto.getDefaultSlaDays());
        if (dto.getDocumentTemplateUrl() != null) entity.setDocumentTemplateUrl(dto.getDocumentTemplateUrl());
        entity.setUpdatedAt(LocalDateTime.now());
        RequestTypeCatalog saved = typeRepo.save(entity);
        auditService.log(actorId(auth), "UPDATE_REQUEST_TYPE", "RequestTypeCatalog", id, null, null);
        return toDto(saved);
    }

    public void deactivate(Long id, Authentication auth) {
        RequestTypeCatalog entity = findOrThrow(id);
        entity.setIsActive(false);
        entity.setUpdatedAt(LocalDateTime.now());
        typeRepo.save(entity);
        auditService.log(actorId(auth), "DEACTIVATE_REQUEST_TYPE", "RequestTypeCatalog", id, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    RequestTypeCatalog findOrThrow(Long id) {
        return typeRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.REQUEST_TYPE_NOT_FOUND, "Type introuvable: id=" + id));
    }

    RequestTypeResponseDto toDto(RequestTypeCatalog t) {
        RequestTypeResponseDto dto = new RequestTypeResponseDto();
        dto.setId(t.getId());
        dto.setPaysId(t.getPaysId());
        dto.setTypeCode(t.getTypeCode());
        dto.setDisplayNameFr(t.getDisplayNameFr());
        dto.setDisplayNameEn(t.getDisplayNameEn());
        dto.setDescription(t.getDescription());
        dto.setCategory(t.getCategory());
        dto.setApprovalLevel(t.getApprovalLevel());
        dto.setDefaultSlaDays(t.getDefaultSlaDays());
        dto.setDocumentTemplateUrl(t.getDocumentTemplateUrl());
        dto.setIsActive(t.getIsActive());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }

    // ── 15 default types ──────────────────────────────────────────────────────

    record SeedType(String typeCode, String nameFr, String nameEn, String description,
                    RequestCategory category, String approvalLevel, int slaDays) {}

    static final List<SeedType> DEFAULT_TYPES = List.of(
        new SeedType("ATTESTATION_TRAVAIL",    "Attestation de travail",          "Employment Certificate",
                     "Attestation prouvant la relation de travail",                RequestCategory.DOCUMENT, "L1", 2),
        new SeedType("ATTESTATION_SALAIRE",    "Attestation de salaire",           "Salary Certificate",
                     "Attestation indiquant le salaire mensuel",                   RequestCategory.DOCUMENT, "L1", 2),
        new SeedType("BULLETIN_PAIE",          "Duplicata bulletin de paie",       "Pay Slip Duplicate",
                     "Demande d'un duplicata de fiche de paie",                    RequestCategory.DOCUMENT, "L1", 2),
        new SeedType("ATTESTATION_ANCIENNETE", "Attestation d'ancienneté",         "Seniority Certificate",
                     "Attestation précisant la durée de service",                  RequestCategory.DOCUMENT, "L1", 3),
        new SeedType("ATTESTATION_CONGE",      "Attestation de congé",             "Leave Certificate",
                     "Attestation de prise en charge de congé approuvé",           RequestCategory.DOCUMENT, "L1", 2),
        new SeedType("CHANGEMENT_ADRESSE",     "Mise à jour adresse domicile",     "Home Address Update",
                     "Mise à jour de l'adresse personnelle dans le dossier RH",    RequestCategory.PERSONAL_DATA_CHANGE, "L1", 3),
        new SeedType("CHANGEMENT_EMAIL",       "Mise à jour email personnel",      "Personal Email Update",
                     "Mise à jour de l'adresse e-mail personnelle",                RequestCategory.PERSONAL_DATA_CHANGE, "L1", 2),
        new SeedType("CHANGEMENT_TELEPHONE",   "Mise à jour téléphone personnel",  "Phone Update",
                     "Mise à jour du numéro de téléphone personnel",               RequestCategory.PERSONAL_DATA_CHANGE, "L1", 2),
        new SeedType("CHANGEMENT_URGENCE",     "Mise à jour contact d'urgence",    "Emergency Contact Update",
                     "Mise à jour du contact d'urgence",                           RequestCategory.PERSONAL_DATA_CHANGE, "L1", 3),
        new SeedType("CHANGEMENT_PHOTO",       "Mise à jour photo de profil",      "Profile Photo Update",
                     "Demande de mise à jour de la photo du profil RH",            RequestCategory.PERSONAL_DATA_CHANGE, "L1", 2),
        new SeedType("MISE_A_JOUR_BANCAIRE",   "Mise à jour coordonnées bancaires","Bank Details Update",
                     "Modification IBAN, RIB ou compte bancaire — validation L2",  RequestCategory.BANK_DETAILS, "L2", 5),
        new SeedType("DEMANDE_FORMATION",      "Demande de formation",             "Training Request",
                     "Demande d'inscription à une formation professionnelle",       RequestCategory.CAREER, "L1", 7),
        new SeedType("MUTATION_INTERNE",       "Demande de mutation interne",      "Internal Transfer Request",
                     "Demande de changement de département ou de site",            RequestCategory.CAREER, "L1", 5),
        new SeedType("TELETRAVAIL_PONCTUEL",   "Demande télétravail ponctuel",     "Ad-hoc Remote Work Request",
                     "Demande de télétravail hors politique habituelle",            RequestCategory.CAREER, "L1", 5),
        new SeedType("AUTRE",                  "Autre demande RH",                 "Other HR Request",
                     "Toute autre demande ne correspondant pas aux catégories ci-dessus", RequestCategory.OTHER, "L1", 5)
    );
}
