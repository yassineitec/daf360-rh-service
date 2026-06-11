package com.daf360.rh.repository;

import com.daf360.rh.domain.ItAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItAssetRepository extends JpaRepository<ItAsset, Long> {

    List<ItAsset> findByProvisioningId(Long provisioningId);

    void deleteByProvisioningId(Long provisioningId);
}
