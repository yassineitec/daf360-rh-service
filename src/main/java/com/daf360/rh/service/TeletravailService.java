package com.daf360.rh.service;

import com.daf360.rh.domain.Teletravail;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.leave.TeletravailCreateDto;
import com.daf360.rh.dto.leave.TeletravailResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.TeletravailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Thin HR wrapper over the existing [teletravails] table.
 * HR endpoints: /api/hr/teletravails — no conflict with Timesheet.
 * Table structure is read-only: no column additions.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class TeletravailService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final TeletravailRepository     teletravailRepo;
    private final EmployeeProfileRepository profileRepo;
    private final AuditService              auditService;

    public TeletravailResponseDto create(Long profileId, TeletravailCreateDto dto,
                                          Authentication auth) {
        Long userId = resolveUserId(profileId);
        OffsetDateTime date = dto.getDate().atStartOfDay(PARIS).toOffsetDateTime();

        if (!teletravailRepo.findOverlapping(userId, date, date).isEmpty()) {
            throw new AppException(ErrorCode.LEAVE_OVERLAP,
                    "Un télétravail est déjà enregistré pour ce jour");
        }

        Teletravail tt = Teletravail.builder()
                .collaborateurId(userId)
                .dateTeletravail(date)
                .etatDemande(DemandeEtat.EN_ATTENTE)
                .reason(dto.getReason())
                .createdAt(OffsetDateTime.now(PARIS))
                .build();

        Teletravail saved = teletravailRepo.save(tt);
        auditService.log(actorId(auth), "CREATE_TELETRAVAIL", "Teletravail", saved.getId(), null, null);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<TeletravailResponseDto> list(Long profileId, Pageable pageable) {
        Long userId = resolveUserId(profileId);
        return teletravailRepo
                .findByCollaborateurIdOrderByDateTeletravailDesc(userId, pageable)
                .map(this::toDto);
    }

    public TeletravailResponseDto approve(Long id, Long responsableId, Authentication auth) {
        Teletravail tt = findOrThrow(id);
        if (tt.getEtatDemande() != DemandeEtat.EN_ATTENTE) {
            throw new AppException(ErrorCode.INVALID_TRANSITION);
        }
        tt.setEtatDemande(DemandeEtat.VALIDE);
        tt.setResponsableId(responsableId);
        tt.setDateValidation(OffsetDateTime.now(PARIS));
        tt.setUpdatedAt(OffsetDateTime.now(PARIS));
        Teletravail saved = teletravailRepo.save(tt);
        auditService.log(actorId(auth), "APPROVE_TELETRAVAIL", "Teletravail", id, null, "VALIDE");
        return toDto(saved);
    }

    public TeletravailResponseDto refuse(Long id, String motif, Authentication auth) {
        Teletravail tt = findOrThrow(id);
        tt.setEtatDemande(DemandeEtat.REFUSE);
        tt.setMotifRefus(motif);
        tt.setUpdatedAt(OffsetDateTime.now(PARIS));
        Teletravail saved = teletravailRepo.save(tt);
        auditService.log(actorId(auth), "REFUSE_TELETRAVAIL", "Teletravail", id, null, "REFUSE | " + motif);
        return toDto(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long resolveUserId(Long profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND))
                .getUserId();
    }

    private Teletravail findOrThrow(Long id) {
        return teletravailRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.TELETRAVAIL_NOT_FOUND));
    }

    TeletravailResponseDto toDto(Teletravail t) {
        TeletravailResponseDto dto = new TeletravailResponseDto();
        dto.setId(t.getId());
        dto.setCollaborateurId(t.getCollaborateurId());
        dto.setResponsableId(t.getResponsableId());
        dto.setResponsableAdjointId(t.getResponsableAdjointId());
        dto.setDate(t.getDateTeletravail() != null
                ? t.getDateTeletravail().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setEtatDemande(t.getEtatDemande());
        dto.setReason(t.getReason());
        dto.setMotifRefus(t.getMotifRefus());
        dto.setDateValidation(t.getDateValidation());
        dto.setCreatedAt(t.getCreatedAt());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
