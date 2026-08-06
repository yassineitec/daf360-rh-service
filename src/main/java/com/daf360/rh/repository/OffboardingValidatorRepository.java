package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingValidator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OffboardingValidatorRepository extends JpaRepository<OffboardingValidator, Long> {

    /** At most one row per pays — the table's unique constraint guarantees it. */
    Optional<OffboardingValidator> findByPaysId(Long paysId);
}
