package com.daf360.rh.repository;

import com.daf360.rh.domain.ParametrageHSPays;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParametrageHSPaysRepository extends JpaRepository<ParametrageHSPays, Long> {

    /** Get the active rule for a country */
    Optional<ParametrageHSPays> findByPaysIdAndActifTrue(Long paysId);

    /** All rules for a country (active + historical) */
    List<ParametrageHSPays> findByPaysIdOrderByDateCreationDesc(Long paysId);

    /** All active rules (for admin listing) */
    List<ParametrageHSPays> findByActifTrueOrderByPaysId();
}
