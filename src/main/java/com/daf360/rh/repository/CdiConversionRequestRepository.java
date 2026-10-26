package com.daf360.rh.repository;

import com.daf360.rh.domain.CdiConversionRequest;
import com.daf360.rh.domain.enums.CdiConversionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CdiConversionRequestRepository extends JpaRepository<CdiConversionRequest, Long> {

    /** Latest request of a contract — what the profile's contract card shows. */
    Optional<CdiConversionRequest> findFirstByContractIdOrderByCreatedAtDescIdDesc(Long contractId);

    List<CdiConversionRequest> findByContractIdAndStatusIn(Long contractId, Collection<CdiConversionStatus> statuses);

    List<CdiConversionRequest> findAllByOrderByCreatedAtDescIdDesc();

    List<CdiConversionRequest> findByPaysIdInOrderByCreatedAtDescIdDesc(Collection<Long> paysIds);
}
