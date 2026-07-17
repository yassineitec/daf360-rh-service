package com.daf360.rh.lists;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ConfigurableListService {

    private final ConfigurableListTypeRepository  typeRepo;
    private final ConfigurableListValueRepository valueRepo;
    private final ConfigurableListMapper          mapper;
    private final AuditService                    auditService;

    // ── In-memory TTL cache ──────────────────────────────────────────────────

    private final Map<String, List<ListValueResponse>> valueCache     = new ConcurrentHashMap<>();
    private final Map<String, Instant>                 cacheTimestamp = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    // ── Public API ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ListValueResponse> getListValues(String listTypeCode, Long paysId) {
        String cacheKey = listTypeCode + "_" + (paysId != null ? paysId : "all");

        Instant timestamp = cacheTimestamp.get(cacheKey);
        if (timestamp != null && Duration.between(timestamp, Instant.now()).compareTo(CACHE_TTL) < 0) {
            log.debug("Cache hit for key={}", cacheKey);
            return valueCache.get(cacheKey);
        }

        ConfigurableListType type = typeRepo.findByCode(listTypeCode)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Liste introuvable : code=" + listTypeCode));

        List<ConfigurableListValue> values;
        if (Boolean.TRUE.equals(type.getIsPerPays()) && paysId != null) {
            values = valueRepo.findActiveByListTypeAndPays(type.getId(), paysId);
        } else {
            values = valueRepo.findByListTypeIdAndPaysIdIsNullAndIsActiveTrueOrderBySortOrderAsc(type.getId());
        }

        List<ListValueResponse> result = toValueResponseList(values, type.getId());
        valueCache.put(cacheKey, result);
        cacheTimestamp.put(cacheKey, Instant.now());
        return result;
    }

    @Transactional(readOnly = true)
    public List<ListTypeResponse> getListTypes() {
        return mapper.toTypeResponseList(typeRepo.findAllByOrderByCodeAsc());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public List<ListValueResponse> getAllValuesForAdmin(Long listTypeId) {
        List<ConfigurableListValue> values =
                valueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(listTypeId);
        return toValueResponseList(values, listTypeId);
    }

    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ListValueResponse createListValue(CreateListValueRequest dto, Long createdBy) {
        boolean duplicate = valueRepo.existsByListTypeIdAndPaysIdAndValueCode(
                dto.getListTypeId(), dto.getPaysId(), dto.getValueCode());
        if (duplicate) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Valeur déjà existante : code=" + dto.getValueCode());
        }

        ConfigurableListValue entity = ConfigurableListValue.builder()
                .listTypeId(dto.getListTypeId())
                .paysId(dto.getPaysId())
                .valueCode(dto.getValueCode())
                .labelFr(dto.getLabelFr())
                .labelEn(dto.getLabelEn())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .isActive(true)
                .isSystem(false)
                .createdBy(createdBy)
                .createdAt(OffsetDateTime.now())
                .build();

        ConfigurableListValue saved = valueRepo.save(entity);
        invalidateCacheForType(dto.getListTypeId());
        auditService.log(
                createdBy != null ? createdBy.toString() : "SYSTEM",
                "CREATE_LIST_VALUE",
                "ConfigurableListValue",
                saved.getId(),
                null,
                "valueCode=" + saved.getValueCode()
        );
        return toValueResponse(saved, saved.getListTypeId());
    }

    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ListValueResponse updateListValue(Long id, UpdateListValueRequest dto, Long updatedBy) {
        ConfigurableListValue value = valueRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Valeur de liste introuvable : id=" + id));

        String before = valueSnapshot(value);

        if (dto.getLabelFr()   != null) value.setLabelFr(dto.getLabelFr());
        if (dto.getLabelEn()   != null) value.setLabelEn(dto.getLabelEn());
        if (dto.getSortOrder() != null) value.setSortOrder(dto.getSortOrder());

        if (dto.getIsActive() != null) {
            if (Boolean.FALSE.equals(dto.getIsActive())) {
                if (Boolean.TRUE.equals(value.getIsSystem())
                        && !Boolean.TRUE.equals(dto.getForceDeactivate())) {
                    throw new AppException(ErrorCode.INVALID_TRANSITION,
                            "Valeur système non désactivable — passez forceDeactivate=true pour forcer");
                }
                value.setIsActive(false);
            } else {
                value.setIsActive(true);
            }
        }

        ConfigurableListValue saved = valueRepo.save(value);
        invalidateCacheForType(saved.getListTypeId());
        auditService.log(
                updatedBy != null ? updatedBy.toString() : "SYSTEM",
                "UPDATE_LIST_VALUE",
                "ConfigurableListValue",
                id,
                before,
                valueSnapshot(saved)
        );
        return toValueResponse(saved, saved.getListTypeId());
    }

    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public void deleteListValue(Long id, Long deletedBy) {
        ConfigurableListValue value = valueRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Valeur de liste introuvable : id=" + id));

        if (Boolean.TRUE.equals(value.getIsSystem())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Valeur système non supprimable");
        }

        auditService.log(
                deletedBy != null ? deletedBy.toString() : "SYSTEM",
                "DELETE_LIST_VALUE",
                "ConfigurableListValue",
                id,
                valueSnapshot(value),
                null
        );
        valueRepo.delete(value);
        invalidateCacheForType(value.getListTypeId());
    }

    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public void reorderListValues(Long listTypeId, List<Long> orderedIds, Long updatedBy) {
        List<ConfigurableListValue> toUpdate = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            Long valueId = orderedIds.get(i);
            ConfigurableListValue value = valueRepo.findById(valueId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                            "Valeur de liste introuvable lors du réordonnancement : id=" + valueId));
            value.setSortOrder(i);
            toUpdate.add(value);
        }
        valueRepo.saveAll(toUpdate);
        invalidateCacheForType(listTypeId);
        auditService.log(
                updatedBy != null ? updatedBy.toString() : "SYSTEM",
                "REORDER_LIST_VALUES",
                "ConfigurableListType",
                listTypeId,
                null,
                "orderedIds=" + orderedIds
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void invalidateCacheForType(Long listTypeId) {
        typeRepo.findById(listTypeId).ifPresent(type -> invalidateCacheForCode(type.getCode()));
    }

    private void invalidateCacheForCode(String code) {
        valueCache.keySet().removeIf(k -> k.startsWith(code));
        cacheTimestamp.keySet().removeIf(k -> k.startsWith(code));
        log.debug("Cache invalidated for list type code={}", code);
    }

    /**
     * Maps a single entity to its response DTO, injecting listTypeId explicitly
     * because the mapper's @Mapping references a nested association that is not
     * present on the flat entity (listTypeId is a bare Long column, not a JPA relation).
     */
    private ListValueResponse toValueResponse(ConfigurableListValue v, Long listTypeId) {
        ListValueResponse r = new ListValueResponse();
        r.setId(v.getId());
        r.setListTypeId(listTypeId);
        r.setPaysId(v.getPaysId());
        r.setValueCode(v.getValueCode());
        r.setLabelFr(v.getLabelFr());
        r.setLabelEn(v.getLabelEn());
        r.setSortOrder(v.getSortOrder());
        r.setIsActive(v.getIsActive());
        r.setIsSystem(v.getIsSystem());
        r.setCreatedAt(v.getCreatedAt());
        r.setUpdatedAt(v.getUpdatedAt());
        return r;
    }

    private List<ListValueResponse> toValueResponseList(List<ConfigurableListValue> values,
                                                        Long listTypeId) {
        List<ListValueResponse> result = new ArrayList<>(values.size());
        for (ConfigurableListValue v : values) {
            result.add(toValueResponse(v, listTypeId));
        }
        return result;
    }

    private String valueSnapshot(ConfigurableListValue v) {
        return "valueCode=" + v.getValueCode()
                + ",labelFr=" + v.getLabelFr()
                + ",isActive=" + v.getIsActive()
                + ",sortOrder=" + v.getSortOrder();
    }
}
