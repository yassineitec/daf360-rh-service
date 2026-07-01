package com.daf360.rh.service;

import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CreateInterviewTypeRequest;
import com.daf360.rh.dto.interview.InterviewTypeDto;
import com.daf360.rh.dto.interview.UpdateInterviewTypeRequest;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class InterviewTypeService {

    private final InterviewTypeRepository      typeRepo;
    private final CandidateInterviewRepository interviewRepo;
    private final TenantService                tenantService;

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public List<InterviewTypeDto> list() {
        Long paysId = tenantService.getEffectivePaysId();
        List<InterviewType> types = (paysId == null)
                ? typeRepo.findAll()
                : typeRepo.findByPaysIdOrderByOrderIndexAsc(paysId);
        return types.stream().map(this::toDto).toList();
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public List<InterviewTypeDto> listActive(Long paysId) {
        return typeRepo.findByPaysIdAndIsActiveTrueOrderByOrderIndexAsc(paysId)
                .stream().map(this::toDto).toList();
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewTypeDto create(CreateInterviewTypeRequest req, Long actorId) {
        InterviewType type = InterviewType.builder()
                .paysId(req.paysId())
                .name(req.name())
                .description(req.description())
                .orderIndex(req.orderIndex() != null ? req.orderIndex() : 0)
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build();
        return toDto(typeRepo.save(type));
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewTypeDto update(Long id, UpdateInterviewTypeRequest req, Long actorId) {
        InterviewType type = findAndCheckTenant(id);
        type.setName(req.name());
        type.setDescription(req.description());
        if (req.orderIndex() != null) {
            type.setOrderIndex(req.orderIndex());
        }
        type.setUpdatedAt(OffsetDateTime.now());
        return toDto(typeRepo.save(type));
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewTypeDto deactivate(Long id, Long actorId) {
        InterviewType type = findAndCheckTenant(id);
        long plannedCount = interviewRepo.countByInterviewTypeIdAndStatus(id, InterviewStatus.PLANNED);
        if (plannedCount > 0) {
            throw new BusinessRuleException(
                    "Impossible de désactiver ce type d'entretien : "
                    + plannedCount + " entretien(s) planifié(s) l'utilisent encore");
        }
        type.setIsActive(false);
        type.setUpdatedAt(OffsetDateTime.now());
        return toDto(typeRepo.save(type));
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewTypeDto activate(Long id, Long actorId) {
        InterviewType type = findAndCheckTenant(id);
        type.setIsActive(true);
        type.setUpdatedAt(OffsetDateTime.now());
        return toDto(typeRepo.save(type));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private InterviewType findAndCheckTenant(Long id) {
        InterviewType type = typeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewType", id));
        Long effectivePaysId = tenantService.getEffectivePaysId();
        if (effectivePaysId != null && !effectivePaysId.equals(type.getPaysId())) {
            throw new BusinessRuleException("Accès refusé : ce type d'entretien appartient à une autre entité");
        }
        return type;
    }

    private InterviewTypeDto toDto(InterviewType t) {
        return new InterviewTypeDto(
                t.getId(), t.getPaysId(), t.getName(),
                t.getDescription(), t.getOrderIndex(), t.getIsActive());
    }
}
