package com.daf360.rh.lifecycle;

import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.lists.ContractTypeRefs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bridges a candidate's employment_type_id (a CONTRACT_TYPE configurable_list_values id)
 * to what the Employee Lifecycle Engine needs.
 *
 * <p>The engine stores the list id on the contract and applies the rules of the value's
 * nature (CDI, CDD, CIVP, STAGE, FREELANCE, DETACHEMENT — see {@link ContractTypeRefs}).
 * The former hard-coded map sent FREELANCE to PORTAGE, a code neither contract_type_config
 * nor employee_contracts knew, so hiring a freelance failed.
 */
@Component
@RequiredArgsConstructor
public class ContractTypeBridge {

    private final ConfigurableListValueRepository listValueRepo;

    /**
     * Returns the CONTRACT_TYPE list value code (Admin › Listes configurables) for the given
     * employment_type_id FK, unmapped. Null when the ID is null or the value no longer exists.
     */
    public String resolveListValueCode(Long employmentTypeId) {
        if (employmentTypeId == null) return null;
        return listValueRepo.findById(employmentTypeId)
                .map(v -> v.getValueCode())
                .orElse(null);
    }

    /**
     * What to pass as {@code CreateContractRequest.contractTypeCode} for a candidate: the list
     * id itself (the contract stores it), or CDI when the candidate has no usable type.
     */
    public String resolveContractTypeRef(Long employmentTypeId) {
        if (employmentTypeId == null) return ContractTypeRefs.DEFAULT_NATURE;
        return listValueRepo.findById(employmentTypeId)
                .map(v -> String.valueOf(v.getId()))
                .orElse(ContractTypeRefs.DEFAULT_NATURE);
    }

    /**
     * Returns the lifecycle nature (CDI, CDD…) of the given employment_type_id FK — the value's
     * lifecycle_nature. Falls back to "CDI" if the ID is null or the value no longer exists.
     */
    public String resolveContractTypeCode(Long employmentTypeId) {
        if (employmentTypeId == null) return ContractTypeRefs.DEFAULT_NATURE;
        return listValueRepo.findById(employmentTypeId)
                .map(ContractTypeRefs::natureOf)
                .orElse(ContractTypeRefs.DEFAULT_NATURE);
    }
}
