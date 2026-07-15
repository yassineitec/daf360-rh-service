package com.daf360.rh.repository;

import com.daf360.rh.domain.OffboardingTaskCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OffboardingTaskCatalogRepository
        extends JpaRepository<OffboardingTaskCatalog, Long> {

    List<OffboardingTaskCatalog> findByPaysIdAndContractTypeAndIsActiveTrueOrderByOrderIndexAsc(
            Long paysId, String contractType);
}
