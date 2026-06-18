package com.daf360.rh.repository;

import com.daf360.rh.domain.ContractTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractTypeConfigRepository extends JpaRepository<ContractTypeConfig, Long> {

    Optional<ContractTypeConfig> findByPaysIdAndContractTypeCode(Long paysId, String contractTypeCode);

    List<ContractTypeConfig> findByPaysId(Long paysId);
}
