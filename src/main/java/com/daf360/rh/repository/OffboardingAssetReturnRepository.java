package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingAssetReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OffboardingAssetReturnRepository
        extends JpaRepository<OffboardingAssetReturn, Long> {

    List<OffboardingAssetReturn> findByWorkflowInstanceId(Long instanceId);

    @Query("""
            SELECT a FROM OffboardingAssetReturn a
            WHERE a.actualReturnDate IS NULL
              AND a.isWrittenOff = false
              AND a.expectedReturnDate < :cutoff
            """)
    List<OffboardingAssetReturn> findOverdueAssets(@Param("cutoff") LocalDate cutoff);
}
