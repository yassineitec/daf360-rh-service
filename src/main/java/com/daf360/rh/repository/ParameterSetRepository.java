package com.daf360.rh.repository;

import com.daf360.rh.domain.ParameterSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParameterSetRepository extends JpaRepository<ParameterSet, Long> {

    List<ParameterSet> findByPaysId(Long paysId);

    Optional<ParameterSet> findByPaysIdAndCle(Long paysId, String cle);

    boolean existsByPaysIdAndCle(Long paysId, String cle);

    long countByPaysId(Long paysId);
}
