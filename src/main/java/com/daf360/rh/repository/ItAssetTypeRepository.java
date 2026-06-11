package com.daf360.rh.repository;

import com.daf360.rh.domain.ItAssetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItAssetTypeRepository extends JpaRepository<ItAssetType, Long> {

    List<ItAssetType> findAllByIsActiveTrueOrderBySortOrderAsc();

    Optional<ItAssetType> findByCode(String code);
}
