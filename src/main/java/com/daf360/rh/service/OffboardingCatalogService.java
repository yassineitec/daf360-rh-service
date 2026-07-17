package com.daf360.rh.service;

import com.daf360.rh.domain.OffboardingTaskCatalog;
import com.daf360.rh.dto.offboarding.OffboardingTaskCatalogDto;
import com.daf360.rh.dto.offboarding.SaveCatalogTaskDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.OffboardingTaskCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class OffboardingCatalogService {

    private final OffboardingTaskCatalogRepository repo;

    @Transactional(readOnly = true)
    public List<OffboardingTaskCatalogDto> list(Long paysId, String contractType) {
        List<OffboardingTaskCatalog> tasks = (contractType != null && !contractType.isBlank())
            ? repo.findByPaysIdAndContractTypeOrderByOrderIndexAsc(paysId, contractType)
            : repo.findByPaysIdOrderByContractTypeAscOrderIndexAsc(paysId);
        return tasks.stream().map(this::toDto).collect(Collectors.toList());
    }

    public OffboardingTaskCatalogDto create(SaveCatalogTaskDto dto) {
        if (repo.existsByPaysIdAndContractTypeAndTaskCode(
                dto.getPaysId(), dto.getContractType(), dto.getTaskCode())) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Un tâche avec le code '" + dto.getTaskCode() +
                "' existe déjà pour ce type de contrat.");
        }
        OffboardingTaskCatalog task = OffboardingTaskCatalog.builder()
            .paysId(dto.getPaysId())
            .contractType(dto.getContractType())
            .taskCode(dto.getTaskCode().toUpperCase().replace(" ", "_"))
            .taskLabel(dto.getTaskLabel())
            .ownerRole(dto.getOwnerRole())
            .isMandatory(dto.isMandatory())
            .isBlocking(dto.isBlocking())
            .slaWorkingDays(dto.getSlaWorkingDays())
            .orderIndex(dto.getOrderIndex())
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build();
        return toDto(repo.save(task));
    }

    public OffboardingTaskCatalogDto update(Long id, SaveCatalogTaskDto dto) {
        OffboardingTaskCatalog task = findOrThrow(id);
        if (repo.existsByPaysIdAndContractTypeAndTaskCodeAndIdNot(
                dto.getPaysId(), dto.getContractType(), dto.getTaskCode(), id)) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Un autre tâche avec le code '" + dto.getTaskCode() +
                "' existe déjà pour ce type de contrat.");
        }
        task.setTaskLabel(dto.getTaskLabel());
        task.setOwnerRole(dto.getOwnerRole());
        task.setIsMandatory(dto.isMandatory());
        task.setIsBlocking(dto.isBlocking());
        task.setSlaWorkingDays(dto.getSlaWorkingDays());
        task.setOrderIndex(dto.getOrderIndex());
        return toDto(repo.save(task));
    }

    public OffboardingTaskCatalogDto toggleActive(Long id) {
        OffboardingTaskCatalog task = findOrThrow(id);
        task.setIsActive(!Boolean.TRUE.equals(task.getIsActive()));
        return toDto(repo.save(task));
    }

    public void delete(Long id) {
        OffboardingTaskCatalog task = findOrThrow(id);
        task.setIsActive(false);
        repo.save(task);
    }

    private OffboardingTaskCatalog findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Tâche catalogue introuvable: id=" + id));
    }

    private OffboardingTaskCatalogDto toDto(OffboardingTaskCatalog t) {
        return OffboardingTaskCatalogDto.builder()
            .id(t.getId())
            .paysId(t.getPaysId())
            .contractType(t.getContractType())
            .taskCode(t.getTaskCode())
            .taskLabel(t.getTaskLabel())
            .ownerRole(t.getOwnerRole())
            .isMandatory(t.getIsMandatory())
            .isBlocking(t.getIsBlocking())
            .slaWorkingDays(t.getSlaWorkingDays())
            .orderIndex(t.getOrderIndex())
            .isActive(t.getIsActive())
            .createdAt(t.getCreatedAt())
            .build();
    }
}
