package com.daf360.rh.service;

import com.daf360.rh.domain.HistoriqueContrat;
import com.daf360.rh.lists.ConfigurableListService;
import com.daf360.rh.lists.ConfigurableListValue;
import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.lists.CreateListValueRequest;
import com.daf360.rh.lists.ListValueResponse;
import com.daf360.rh.lists.UpdateListValueRequest;
import com.daf360.rh.dto.contract.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.HistoriqueContratRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ContractHistoryService {

    private final HistoriqueContratRepository histRepo;
    private final EmployeeProfileRepository   profileRepo;
    /**
     * Contract types are the CONTRACT_TYPE configurable list (Admin › Listes configurables ›
     * Type de contrat) — the same list as profiles, candidates and lifecycle contracts. The
     * separate type_contrat table is retired (sql/2026-10-09_historique_type_contrat_list.sql).
     */
    private final ConfigurableListValueRepository listValueRepo;
    private final ConfigurableListService         listService;
    private final ContractTypeRefs                contractTypeRefs;

    // ── Contract types (the CONTRACT_TYPE list) ───────────────────────────────

    @Transactional(readOnly = true)
    public List<TypeContratDto> getAllTypeContrats() {
        return listValueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(contractTypeRefs.listTypeId())
                .stream()
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .sorted(Comparator.comparing(ConfigurableListValue::getLabelFr, String.CASE_INSENSITIVE_ORDER))
                .map(this::toTypeDto)
                .collect(Collectors.toList());
    }

    /** Adds a shared (all entities) CONTRACT_TYPE value; it follows the CDI rules until changed. */
    public TypeContratDto createTypeContrat(TypeContratDto req) {
        CreateListValueRequest create = new CreateListValueRequest();
        create.setListTypeId(contractTypeRefs.listTypeId());
        create.setPaysId(null);
        create.setValueCode(req.getCode() != null && !req.getCode().isBlank() ? req.getCode() : req.getLabelFr());
        create.setLabelFr(req.getLabelFr());
        create.setLabelEn(req.getLabelEn() != null && !req.getLabelEn().isBlank() ? req.getLabelEn() : req.getLabelFr());
        ListValueResponse created = listService.createListValue(create, null);
        return listValueRepo.findById(created.getId()).map(this::toTypeDto).orElseThrow();
    }

    /** Deactivates — never deletes — so the contracts and history entries using it keep their type. */
    public void deleteTypeContrat(Long id) {
        UpdateListValueRequest deactivate = new UpdateListValueRequest();
        deactivate.setIsActive(false);
        listService.updateListValue(id, deactivate, null);
    }

    // ── Contract History ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ContractHistoryDto> getHistory(Long profileId) {
        return histRepo.findByIdCollaborateurOrderByDateEffetDesc(profileId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ContractHistoryDto getActiveContract(Long profileId) {
        return histRepo.findActiveAtDate(profileId, LocalDate.now())
                .stream().findFirst().map(this::toDto).orElse(null);
    }

    public ContractHistoryDto addContract(Long profileId, CreateContractRequest req,
                                          Authentication auth) {
        // Validate profile exists
        if (!profileRepo.existsById(profileId)) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "Profil introuvable: " + profileId);
        }

        // Validate typeContrat
        ConfigurableListValue tc = listValueRepo.findById(req.getIdTypeContrat())
                .filter(v -> v.getListTypeId().equals(contractTypeRefs.listTypeId()))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Type de contrat introuvable: " + req.getIdTypeContrat()));

        // Validate typeDocument
        if (!"CONTRAT_INITIAL".equals(req.getTypeDocument())
                && !"AVENANT".equals(req.getTypeDocument())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "typeDocument doit être CONTRAT_INITIAL ou AVENANT");
        }

        // Business rule: auto-close the previous open contract
        List<HistoriqueContrat> openContracts = histRepo.findOpenContracts(profileId);
        for (HistoriqueContrat open : openContracts) {
            // Close on date_effet - 1 day
            LocalDate closingDate = req.getDateEffet().minusDays(1);
            if (closingDate.isAfter(open.getDateEffet()) || closingDate.equals(open.getDateEffet())) {
                open.setDateFin(closingDate);
                histRepo.save(open);
                log.info("Auto-closed contract id={} on {}", open.getId(), closingDate);
            }
        }

        Long actorId = resolveActorId(auth);
        HistoriqueContrat hc = HistoriqueContrat.builder()
                .idCollaborateur(profileId)
                .typeContrat(tc)
                .typeDocument(req.getTypeDocument())
                .dateEffet(req.getDateEffet())
                .dateFin(req.getDateFin())
                .salaireNet(req.getSalaireNet())
                .motif(req.getMotif())
                .commentaire(req.getCommentaire())
                .createdBy(actorId)
                .dateCreation(OffsetDateTime.now())
                .build();

        HistoriqueContrat saved = histRepo.save(hc);
        log.info("Added contract profileId={} type={} dateEffet={}", profileId,
                req.getTypeDocument(), req.getDateEffet());
        return toDto(saved);
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private TypeContratDto toTypeDto(ConfigurableListValue tc) {
        return TypeContratDto.builder()
                .id(tc.getId()).code(tc.getValueCode())
                .labelFr(tc.getLabelFr()).labelEn(tc.getLabelEn())
                .isActive(tc.getIsActive()).build();
    }

    private ContractHistoryDto toDto(HistoriqueContrat h) {
        boolean isActive = h.getDateFin() == null || !h.getDateFin().isBefore(LocalDate.now());
        return ContractHistoryDto.builder()
                .id(h.getId())
                .idCollaborateur(h.getIdCollaborateur())
                .idTypeContrat(h.getTypeContrat() != null ? h.getTypeContrat().getId() : null)
                .typeContratCode(h.getTypeContrat() != null ? h.getTypeContrat().getValueCode() : null)
                .typeContratLabelFr(h.getTypeContrat() != null ? h.getTypeContrat().getLabelFr() : null)
                .typeDocument(h.getTypeDocument())
                .dateEffet(h.getDateEffet())
                .dateFin(h.getDateFin())
                .salaireNet(h.getSalaireNet())
                .motif(h.getMotif())
                .commentaire(h.getCommentaire())
                .createdBy(h.getCreatedBy())
                .dateCreation(h.getDateCreation())
                .isActive(isActive)
                .build();
    }

    private Long resolveActorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
