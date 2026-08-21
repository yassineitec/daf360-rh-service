package com.daf360.rh.repository;

import com.daf360.rh.domain.ItAssetAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItAssetAssignmentRepository extends JpaRepository<ItAssetAssignment, Long> {

    /**
     * Newest first, and `id` breaks the tie: several items can be handed over on the same
     * day (hire day, typically), and a list whose order changes between two identical
     * requests makes the tab look like it lost a row.
     */
    List<ItAssetAssignment> findByEmployeeProfileIdOrderByAssignedAtDescIdDesc(Long employeeProfileId);

    List<ItAssetAssignment> findByEmployeeProfileIdAndReturnedAtIsNull(Long employeeProfileId);

    /** Guards UQ_it_asg_active_serial before the insert, so the user gets a message not a 500. */
    Optional<ItAssetAssignment> findFirstBySerialNumberAndReturnedAtIsNull(String serialNumber);

    /** De-duplication key for rows seeded from the IT provisioning form. */
    boolean existsByItProvisioningIdAndAssetTypeId(Long itProvisioningId, Long assetTypeId);

    Optional<ItAssetAssignment> findFirstByOffboardingReturnId(Long offboardingReturnId);
}
