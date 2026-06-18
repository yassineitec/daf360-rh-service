package com.daf360.rh.lifecycle;

import com.daf360.rh.lists.ConfigurableListValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps configurable_list_values (EMPLOYMENT_TYPE) value codes to lifecycle
 * contract type codes used by the Employee Lifecycle Engine.
 */
@Component
@RequiredArgsConstructor
public class ContractTypeBridge {

    private final ConfigurableListValueRepository listValueRepo;

    private static final Map<String, String> CODE_MAP = Map.of(
        "CDI",         "CDI",
        "CDD",         "CDD",
        "CIVP",        "CIVP",
        "STAGE",       "STAGE",
        "FREELANCE",   "PORTAGE",
        "PORTAGE",     "PORTAGE",
        "DETACHEMENT", "DETACHEMENT"
    );

    /**
     * Returns the lifecycle contract type code for the given employment_type_id FK.
     * Falls back to "CDI" if the ID is null or the code has no mapping.
     */
    public String resolveContractTypeCode(Long employmentTypeId) {
        if (employmentTypeId == null) return "CDI";
        return listValueRepo.findById(employmentTypeId)
                .map(v -> CODE_MAP.getOrDefault(v.getValueCode(), v.getValueCode()))
                .orElse("CDI");
    }
}
