package com.daf360.rh.repository;

import com.daf360.rh.domain.WorkingTimeRegime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkingTimeRegimeRepository extends JpaRepository<WorkingTimeRegime, Long> {

    List<WorkingTimeRegime> findByPaysIdAndIsActiveTrue(Long paysId);

    Optional<WorkingTimeRegime> findByPaysIdAndCodeAndIsActiveTrue(Long paysId, String code);

    List<WorkingTimeRegime> findByPaysIdAndIsDefaultTrueAndIsActiveTrue(Long paysId);

    Optional<WorkingTimeRegime> findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(Long paysId);

    long countByPaysIdAndIsActiveTrue(Long paysId);

    @Modifying
    @Transactional
    @Query("UPDATE WorkingTimeRegime r SET r.isDefault = false WHERE r.paysId = :paysId AND r.id <> :excludeId AND r.isDefault = true")
    void clearDefaultForPays(@Param("paysId") Long paysId, @Param("excludeId") Long excludeId);
}
