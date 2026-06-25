package com.daf360.rh.repository;

import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItProvisioningRepository extends JpaRepository<ItProvisioning, Long> {

    Optional<ItProvisioning> findByCandidateId(Long candidateId);

    boolean existsByCandidateId(Long candidateId);

    List<ItProvisioning> findByStatusIn(Collection<ItProvisioningStatus> statuses);

    @Query("""
        SELECT p FROM ItProvisioning p
        WHERE p.status IN :statuses
          AND p.candidateId IN (SELECT c.id FROM Candidate c WHERE c.paysId = :paysId)
        """)
    List<ItProvisioning> findByStatusInAndCandidatePaysId(
            @Param("statuses") Collection<ItProvisioningStatus> statuses,
            @Param("paysId")   Long paysId);
}
