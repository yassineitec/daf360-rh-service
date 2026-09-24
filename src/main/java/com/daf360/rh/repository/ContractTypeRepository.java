package com.daf360.rh.repository;

import com.daf360.rh.domain.ContractType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ContractTypeRepository extends JpaRepository<ContractType, Long> {

    List<ContractType> findByPaysIdAndIsActiveTrueOrderByDisplayOrderAsc(Long paysId);
    List<ContractType> findByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * Several entities at once — a role in V74 LIST mode legitimately covers more than one
     * pays, and the single-id finder above keeps only one of them. Mirrors GradeRepository's
     * own overload of the same shape.
     */
    List<ContractType> findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(Collection<Long> paysIds);
}
