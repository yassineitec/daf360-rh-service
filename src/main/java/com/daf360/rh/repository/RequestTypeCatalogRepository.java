package com.daf360.rh.repository;

import com.daf360.rh.domain.RequestTypeCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RequestTypeCatalogRepository extends JpaRepository<RequestTypeCatalog, Long> {

    List<RequestTypeCatalog> findByPaysIdAndIsActiveTrue(Long paysId);

    Optional<RequestTypeCatalog> findByPaysIdAndTypeCode(Long paysId, String typeCode);

    boolean existsByPaysIdAndTypeCode(Long paysId, String typeCode);

    long countByPaysId(Long paysId);
}
