package com.daf360.rh.repository;

import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecruitmentDemandRepository extends JpaRepository<RecruitmentDemand, Long> {

    // No OrderBy in these names: a static OrderBy is applied *before* the Pageable's sort, which
    // made the front's ?sort= a mere tie-breaker. The default (newest first) is now set by the
    // service when the caller sends no sort — see RecruitmentDemandService.withDefaultSort.
    Page<RecruitmentDemand> findByPaysId(Long paysId, Pageable pageable);

    Page<RecruitmentDemand> findByPaysIdAndStatut(
            Long paysId, RecruitmentDemandStatus statut, Pageable pageable);

    Page<RecruitmentDemand> findByCreatedByUserId(Long userId, Pageable pageable);

    Page<RecruitmentDemand> findByCreatedByUserIdAndStatut(
            Long userId, RecruitmentDemandStatus statut, Pageable pageable);

    List<RecruitmentDemand> findByPaysIdAndStatutOrderByJobTitleAsc(
            Long paysId, RecruitmentDemandStatus statut);

    @Query("""
        SELECT COUNT(rd) FROM RecruitmentDemand rd
        WHERE rd.paysId = :paysId AND rd.statut = 'EN_ATTENTE'
        """)
    long countPendingByPays(@Param("paysId") Long paysId);
}
