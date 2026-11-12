package com.daf360.rh.lists;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.security.TenantContext;
import com.daf360.rh.security.TenantService;
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
    private final TenantService                   tenantService;
    private final com.daf360.rh.repository.EmployeeProfileRepository profileRepo;

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
        assertCanWritePays(dto.getPaysId());
        // A code is matched by value elsewhere (ContractTypeRefs…): never store stray spaces.
        String valueCode = dto.getValueCode() != null ? dto.getValueCode().trim() : null;
        boolean duplicate = valueRepo.existsByListTypeIdAndPaysIdAndValueCode(
                dto.getListTypeId(), dto.getPaysId(), valueCode);
        if (duplicate) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Valeur déjà existante : code=" + valueCode);
        }

        ConfigurableListValue entity = ConfigurableListValue.builder()
                .listTypeId(dto.getListTypeId())
                .paysId(dto.getPaysId())
                .valueCode(valueCode)
                .labelFr(dto.getLabelFr())
                .labelEn(dto.getLabelEn())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .lifecycleNature(lifecycleNatureFor(dto.getListTypeId(), dto.getLifecycleNature(), valueCode))
                .decisionAction(decisionActionFor(dto.getListTypeId(), dto.getDecisionAction(), valueCode))
                .isActive(true)
                .isSystem(false)
                .createdBy(createdBy)
                .createdAt(OffsetDateTime.now())
                .build();

        applyDecisionRules(entity, dto.getCustomDecisions(), dto.getTrialRenewable(),
                dto.getTrialMaxRenewals(), dto.getTrialDecisions(), dto.getEndDecisions());

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

    /**
     * CONTRACT_DECISION only: the action the choice carries out — the one asked for, else the
     * code's own (a value coded RENEW_TRIAL renews the trial). The admin names the choice
     * freely; the engine only knows these actions. Other lists carry none (null).
     */
    private String decisionActionFor(Long listTypeId, String requested, String valueCode) {
        boolean decisionList = typeRepo.findById(listTypeId)
                .map(t -> com.daf360.rh.lifecycle.ContractDecisionService.LIST_CODE.equals(t.getCode()))
                .orElse(false);
        if (!decisionList) return null;
        String code = requested != null && !requested.isBlank() ? requested : valueCode;
        return com.daf360.rh.lifecycle.ContractDecisionAction.parse(code)
                .map(Enum::name)
                .orElseThrow(() -> new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Action de décision inconnue : " + code + " — attendu : "
                        + com.daf360.rh.lifecycle.ContractDecisionAction.codes()));
    }

    /**
     * CONTRACT_TYPE only: the lifecycle nature to store — the one asked for (validated), else
     * the code's own (CDD → CDD), else CDI. Other lists carry no nature (null).
     */
    private String lifecycleNatureFor(Long listTypeId, String requested, String valueCode) {
        boolean contractType = typeRepo.findById(listTypeId)
                .map(t -> ContractTypeRefs.LIST_CODE.equals(t.getCode()))
                .orElse(false);
        if (!contractType) return null;
        if (requested == null || requested.isBlank()) {
            return ContractTypeRefs.natureOf(ConfigurableListValue.builder().valueCode(valueCode).build());
        }
        String nature = requested.trim().toUpperCase();
        if (!ContractTypeRefs.NATURES.contains(nature)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Nature de contrat inconnue : " + requested + " — attendu : " + ContractTypeRefs.NATURES);
        }
        return nature;
    }

    /**
     * CONTRACT_TYPE only: the type's own « Décider » rules. {@code custom} true stores them
     * (an empty list = no choice offered at that moment), false clears them so the type
     * inherits its nature's rules again, null leaves them as they are. Ignored elsewhere.
     */
    private void applyDecisionRules(ConfigurableListValue v, Boolean custom, Boolean renewable,
                                    Integer maxRenewals, List<String> trial, List<String> end) {
        if (custom == null) return;
        boolean contractType = typeRepo.findById(v.getListTypeId())
                .map(t -> ContractTypeRefs.LIST_CODE.equals(t.getCode()))
                .orElse(false);
        if (!contractType) return;
        if (!custom) {
            v.setTrialRenewable(null);
            v.setTrialMaxRenewals(null);
            v.setTrialDecisions(null);
            v.setEndDecisions(null);
            return;
        }
        if (maxRenewals != null && (maxRenewals < 0 || maxRenewals > 10)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le nombre de renouvellements doit être compris entre 0 et 10.");
        }
        v.setTrialRenewable(Boolean.TRUE.equals(renewable));
        v.setTrialMaxRenewals(maxRenewals != null ? maxRenewals : 1);
        v.setTrialDecisions(com.daf360.rh.lifecycle.EmployeeLifecycleService.decisionsCsv(
                trial != null ? trial : List.of(), com.daf360.rh.lifecycle.ContractDecisionAction.Moment.TRIAL_END));
        v.setEndDecisions(com.daf360.rh.lifecycle.EmployeeLifecycleService.decisionsCsv(
                end != null ? end : List.of(), com.daf360.rh.lifecycle.ContractDecisionAction.Moment.CONTRACT_END));
    }

    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ListValueResponse updateListValue(Long id, UpdateListValueRequest dto, Long updatedBy) {
        ConfigurableListValue value = valueRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Valeur de liste introuvable : id=" + id));
        assertCanWritePays(value.getPaysId());

        String before = valueSnapshot(value);

        if (dto.getLabelFr()   != null) value.setLabelFr(dto.getLabelFr());
        if (dto.getLabelEn()   != null) value.setLabelEn(dto.getLabelEn());
        if (dto.getSortOrder() != null) value.setSortOrder(dto.getSortOrder());
        if (dto.getPayrollContractCode() != null) {
            value.setPayrollContractCode(dto.getPayrollContractCode().trim().toUpperCase());
        }
        if (dto.getLifecycleNature() != null) {
            value.setLifecycleNature(lifecycleNatureFor(value.getListTypeId(), dto.getLifecycleNature(), value.getValueCode()));
        }
        if (dto.getDecisionAction() != null) {
            value.setDecisionAction(decisionActionFor(value.getListTypeId(), dto.getDecisionAction(), value.getValueCode()));
        }
        applyDecisionRules(value, dto.getCustomDecisions(), dto.getTrialRenewable(),
                dto.getTrialMaxRenewals(), dto.getTrialDecisions(), dto.getEndDecisions());

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
        assertCanWritePays(value.getPaysId());

        if (Boolean.TRUE.equals(value.getIsSystem())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Valeur système non supprimable");
        }
        // employee_profiles.contract_type stores this row's id as varchar — no FK can protect it.
        if (profileRepo.existsByContractTypeId(String.valueOf(id))) {
            throw new AppException(ErrorCode.LIST_VALUE_IN_USE);
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

    /**
     * An entity's own values (CONTRACT_TYPE of Égypte…) may only be written by that entity's
     * administrators or by a cross-country admin. ADMIN_LISTS alone used to open every
     * entity's lists, because the screen had no way to target another one — it now does.
     *
     * Shared values (pays_id NULL) keep the old rule, ADMIN_LISTS alone: every global list is
     * made of them today. A token with no pays claim is let through, like TenantService's own
     * reads, rather than locking an incomplete session out of a screen it could use before.
     */
    private void assertCanWritePays(Long paysId) {
        if (paysId == null || tenantService.isAdmin()) return;
        Long own = TenantContext.get();
        if (own != null && !own.equals(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Vous ne pouvez gérer que les valeurs de votre entité.");
        }
    }

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
        r.setPayrollContractCode(v.getPayrollContractCode());
        r.setLifecycleNature(v.getLifecycleNature());
        r.setDecisionAction(v.getDecisionAction());
        r.setCustomDecisions(v.hasOwnDecisionRules());
        if (v.hasOwnDecisionRules()) {
            r.setTrialRenewable(v.getTrialRenewable());
            r.setTrialMaxRenewals(v.getTrialMaxRenewals());
            r.setTrialDecisions(csvToList(v.getTrialDecisions()));
            r.setEndDecisions(csvToList(v.getEndDecisions()));
        }
        r.setCreatedAt(v.getCreatedAt());
        r.setUpdatedAt(v.getUpdatedAt());
        return r;
    }

    private static List<String> csvToList(String csv) {
        if (csv == null) return null;
        return com.daf360.rh.lifecycle.ContractDecisionAction.parseCsv(csv).stream().map(Enum::name).toList();
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
                + ",sortOrder=" + v.getSortOrder()
                + (v.hasOwnDecisionRules()
                    ? ",trialRenewable=" + v.getTrialRenewable() + ",trialMaxRenewals=" + v.getTrialMaxRenewals()
                      + ",trialDecisions=" + v.getTrialDecisions() + ",endDecisions=" + v.getEndDecisions()
                    : "");
    }
}
