package com.daf360.rh.service;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.leave.AutorisationCreateDto;
import com.daf360.rh.dto.leave.AutorisationResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.AutorisationRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Thin HR wrapper over the existing [autorisations] table.
 * HR endpoints: /api/hr/autorisations — no conflict with Timesheet.
 * Table structure is read-only: no column additions.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AutorisationService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final AutorisationRepository    autorisationRepo;
    private final EmployeeProfileRepository profileRepo;
    private final AuditService              auditService;

    public AutorisationResponseDto create(Long profileId, AutorisationCreateDto dto,
                                           Authentication auth) {
        Long userId = resolveUserId(profileId);

        Autorisation aut = Autorisation.builder()
                .collaborateurId(userId)
                .dateAutorisation(dto.getDate().atStartOfDay(PARIS).toOffsetDateTime())
                .nombreHeures(dto.getNombreHeures())
                .etatDemande(DemandeEtat.EN_ATTENTE)
                .reason(dto.getReason())
                .createdAt(OffsetDateTime.now(PARIS))
                .build();

        Autorisation saved = autorisationRepo.save(aut);
        auditService.log(actorId(auth), "CREATE_AUTORISATION", "Autorisation", saved.getId(),
                null, dto.getNombreHeures() + "h");
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<AutorisationResponseDto> list(Long profileId, Pageable pageable) {
        Long userId = resolveUserId(profileId);
        return autorisationRepo
                .findByCollaborateurIdOrderByDateAutorisationDesc(userId, pageable)
                .map(this::toDto);
    }

    public AutorisationResponseDto approve(Long id, Long responsableId, Authentication auth) {
        Autorisation aut = findOrThrow(id);
        if (aut.getEtatDemande() != DemandeEtat.EN_ATTENTE) {
            throw new AppException(ErrorCode.INVALID_TRANSITION);
        }
        aut.setEtatDemande(DemandeEtat.VALIDE);
        aut.setResponsableId(responsableId);
        aut.setDateValidation(OffsetDateTime.now(PARIS));
        aut.setUpdatedAt(OffsetDateTime.now(PARIS));
        Autorisation saved = autorisationRepo.save(aut);
        auditService.log(actorId(auth), "APPROVE_AUTORISATION", "Autorisation", id, null, "VALIDE");
        return toDto(saved);
    }

    public AutorisationResponseDto refuse(Long id, String motif, Authentication auth) {
        Autorisation aut = findOrThrow(id);
        aut.setEtatDemande(DemandeEtat.REFUSE);
        aut.setMotifRefus(motif);
        aut.setUpdatedAt(OffsetDateTime.now(PARIS));
        Autorisation saved = autorisationRepo.save(aut);
        auditService.log(actorId(auth), "REFUSE_AUTORISATION", "Autorisation", id, null, "REFUSE | " + motif);
        return toDto(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long resolveUserId(Long profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND))
                .getUserId();
    }

    private Autorisation findOrThrow(Long id) {
        return autorisationRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.AUTORISATION_NOT_FOUND));
    }

    AutorisationResponseDto toDto(Autorisation a) {
        AutorisationResponseDto dto = new AutorisationResponseDto();
        dto.setId(a.getId());
        dto.setCollaborateurId(a.getCollaborateurId());
        dto.setResponsableId(a.getResponsableId());
        dto.setResponsableAdjointId(a.getResponsableAdjointId());
        dto.setDate(a.getDateAutorisation() != null
                ? a.getDateAutorisation().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setNombreHeures(a.getNombreHeures());
        dto.setEtatDemande(a.getEtatDemande());
        dto.setReason(a.getReason());
        dto.setMotifRefus(a.getMotifRefus());
        dto.setDateValidation(a.getDateValidation());
        dto.setCreatedAt(a.getCreatedAt());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
