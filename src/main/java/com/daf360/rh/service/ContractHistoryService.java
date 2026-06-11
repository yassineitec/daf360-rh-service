package com.daf360.rh.service;

import com.daf360.rh.domain.HistoriqueContrat;
import com.daf360.rh.domain.TypeContrat;
import com.daf360.rh.dto.contract.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.HistoriqueContratRepository;
import com.daf360.rh.repository.TypeContratRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ContractHistoryService {

    private final HistoriqueContratRepository histRepo;
    private final TypeContratRepository       typeRepo;
    private final EmployeeProfileRepository   profileRepo;

    // ── TypeContrat CRUD ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TypeContratDto> getAllTypeContrats() {
        return typeRepo.findByIsActiveTrueOrderByLabelFrAsc()
                .stream().map(this::toTypeDto).collect(Collectors.toList());
    }

    public TypeContratDto createTypeContrat(TypeContratDto req) {
        TypeContrat tc = TypeContrat.builder()
                .code(req.getCode() != null ? req.getCode() : req.getLabelFr())
                .labelFr(req.getLabelFr())
                .labelEn(req.getLabelEn() != null ? req.getLabelEn() : req.getLabelFr())
                .build();
        return toTypeDto(typeRepo.save(tc));
    }

    public void deleteTypeContrat(Long id) {
        typeRepo.findById(id).ifPresent(tc -> { tc.setIsActive(false); typeRepo.save(tc); });
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
        TypeContrat tc = typeRepo.findById(req.getIdTypeContrat())
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

    private TypeContratDto toTypeDto(TypeContrat tc) {
        return TypeContratDto.builder()
                .id(tc.getId()).code(tc.getCode())
                .labelFr(tc.getLabelFr()).labelEn(tc.getLabelEn())
                .isActive(tc.getIsActive()).build();
    }

    private ContractHistoryDto toDto(HistoriqueContrat h) {
        boolean isActive = h.getDateFin() == null || !h.getDateFin().isBefore(LocalDate.now());
        return ContractHistoryDto.builder()
                .id(h.getId())
                .idCollaborateur(h.getIdCollaborateur())
                .idTypeContrat(h.getTypeContrat() != null ? h.getTypeContrat().getId() : null)
                .typeContratCode(h.getTypeContrat() != null ? h.getTypeContrat().getCode() : null)
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
