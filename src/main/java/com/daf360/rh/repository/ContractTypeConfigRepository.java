package com.daf360.rh.repository;

import com.daf360.rh.domain.ContractTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractTypeConfigRepository extends JpaRepository<ContractTypeConfig, Long> {

    Optional<ContractTypeConfig> findByPaysIdAndContractTypeCode(Long paysId, String contractTypeCode);

    Optional<ContractTypeConfig> findFirstByContractTypeCodeOrderByIdAsc(String contractTypeCode);

    /**
     * The rules for a contract nature (CDI, CDD…) in a pays. A pays without its own row
     * borrows the reference one — the first seeded, Tunisie (pays 179) — so a hire never
     * fails over missing configuration; an admin can still add a pays-specific row.
     */
    default Optional<ContractTypeConfig> findForNature(Long paysId, String nature) {
        return findByPaysIdAndContractTypeCode(paysId, nature)
                .or(() -> findFirstByContractTypeCodeOrderByIdAsc(nature));
    }

    List<ContractTypeConfig> findByPaysId(Long paysId);
}
