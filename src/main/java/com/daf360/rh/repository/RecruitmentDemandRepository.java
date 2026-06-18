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

    Page<RecruitmentDemand> findByPaysIdOrderBySubmittedAtDesc(Long paysId, Pageable pageable);

    Page<RecruitmentDemand> findByPaysIdAndStatutOrderBySubmittedAtDesc(
            Long paysId, RecruitmentDemandStatus statut, Pageable pageable);

    Page<RecruitmentDemand> findByCreatedByUserIdOrderBySubmittedAtDesc(Long userId, Pageable pageable);

    Page<RecruitmentDemand> findByCreatedByUserIdAndStatutOrderBySubmittedAtDesc(
            Long userId, RecruitmentDemandStatus statut, Pageable pageable);

    List<RecruitmentDemand> findByPaysIdAndStatutOrderByJobTitleAsc(
            Long paysId, RecruitmentDemandStatus statut);

    @Query("""
        SELECT COUNT(rd) FROM RecruitmentDemand rd
        WHERE rd.paysId = :paysId AND rd.statut = 'EN_ATTENTE'
        """)
    long countPendingByPays(@Param("paysId") Long paysId);
}
