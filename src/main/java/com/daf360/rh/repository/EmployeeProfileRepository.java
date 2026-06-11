package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeProfileRepository
        extends JpaRepository<EmployeeProfile, Long>,
                JpaSpecificationExecutor<EmployeeProfile> {

    Optional<EmployeeProfile> findByUserId(Long userId);

    Optional<EmployeeProfile> findByCandidateId(Long candidateId);

    boolean existsByUserId(Long userId);

    Page<EmployeeProfile> findByPaysId(Long paysId, Pageable pageable);

    Page<EmployeeProfile> findByPaysIdAndLifecycleStatus(
            Long paysId, LifecycleStatus status, Pageable pageable);

    /**
     * Filtered + name-search query joining Users for fullName/email matching.
     * All params are optional — pass null to skip that filter.
     */
    @Query(value = """
            SELECT ep.*
            FROM employee_profiles ep
            JOIN [dbo].[Users] u ON ep.user_id = u.id
            LEFT JOIN [dbo].[grades] g ON g.id = ep.grade_id
            LEFT JOIN [dbo].[departments] dept ON dept.id = ep.department_id
            WHERE ep.deleted = 0
              AND (:paysId      IS NULL OR ep.pays_id          = :paysId)
              AND (:status      IS NULL OR ep.lifecycle_status = :status)
              AND (:department  IS NULL OR dept.label_fr       = :department)
              AND (:grade       IS NULL OR g.label_fr          = :grade)
              AND (:contract    IS NULL OR ep.contract_type    = :contract)
              AND (:search      IS NULL
                   OR u.fullName LIKE '%' + :search + '%'
                   OR u.email    LIKE '%' + :search + '%')
            """,
           countQuery = """
            SELECT COUNT(*)
            FROM employee_profiles ep
            JOIN [dbo].[Users] u ON ep.user_id = u.id
            LEFT JOIN [dbo].[grades] g ON g.id = ep.grade_id
            LEFT JOIN [dbo].[departments] dept ON dept.id = ep.department_id
            WHERE ep.deleted = 0
              AND (:paysId      IS NULL OR ep.pays_id          = :paysId)
              AND (:status      IS NULL OR ep.lifecycle_status = :status)
              AND (:department  IS NULL OR dept.label_fr       = :department)
              AND (:grade       IS NULL OR g.label_fr          = :grade)
              AND (:contract    IS NULL OR ep.contract_type    = :contract)
              AND (:search      IS NULL
                   OR u.fullName LIKE '%' + :search + '%'
                   OR u.email    LIKE '%' + :search + '%')
            """,
           nativeQuery = true)
    Page<EmployeeProfile> search(@Param("paysId")      Long paysId,
                                  @Param("status")      String status,
                                  @Param("department")  String department,
                                  @Param("grade")       String grade,
                                  @Param("contract")  String contract,
                                  @Param("search")    String search,
                                  Pageable pageable);

    java.util.List<EmployeeProfile> findByPaysIdAndDeletedFalse(Long paysId);

    long countByRegimeTemplateIdAndDeletedFalse(Long regimeTemplateId);

    long countByPaysIdAndRegimeTemplateIdNotNullAndDeletedFalse(Long paysId);
}
