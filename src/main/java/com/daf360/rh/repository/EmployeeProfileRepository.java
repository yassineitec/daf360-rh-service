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

import java.time.LocalDate;
import java.util.List;
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
     * La liste des employés : recherche par nom/e-mail, filtres facultatifs, et trois
     * clauses qui ne sont PAS facultatives.
     *
     * <h3>1. Les comptes fictifs sortent — {@code u.is_employee = 1}</h3>
     * C'est le prédicat de {@link com.daf360.rh.common.UserScope} : comptes de test,
     * imports dupliqués, comptes machine. Sur cette base, l'annuaire proposait
     * « TimeSheet TUN », « test test » et une vingtaine d'utilisateurs de démonstration
     * au milieu du personnel.
     *
     * <h3>2. La portée pays vient du JETON, pas du filtre</h3>
     * `:scopeAll` / `:paysIds` rendent les trois modes de V74. Le paramètre `:paysId`
     * reste, mais il ne peut que RESTREINDRE à l'intérieur de la portée — jamais l'élargir :
     * la liste déroulante « Pays » de l'écran ne doit pas devenir un moyen de lire une
     * autre entité.
     *
     * <h3>3. Trois statuts par défaut — ACTIVE, ON_LEAVE, ON_MISSION</h3>
     * Un employé en cours d'offboarding, parti, archivé ou pas encore arrivé n'est pas
     * dans l'effectif : il est dans un AUTRE état, avec son propre écran. La liste par
     * défaut est donc l'effectif présent.
     *
     * <p>`:includeInactive = 1` lève cette restriction, et cette porte est nécessaire :
     * sans elle un profil TERMINATED devient introuvable depuis l'application, y compris
     * pour rouvrir un offboarding validé ou consulter une archive. Un filtre `:status`
     * explicite passe outre de la même façon — demander TERMINATED, c'est le vouloir.
     */
    @Query(value = """
            SELECT ep.*
            FROM employee_profiles ep
            JOIN [dbo].[Users] u ON ep.user_id = u.id
            LEFT JOIN [dbo].[grades] g ON g.id = ep.grade_id
            LEFT JOIN [dbo].[departments] dept ON dept.id = ep.department_id
            WHERE ep.deleted = 0
              AND u.is_employee = 1
              AND (:scopeAll = 1 OR ep.pays_id IN (:paysIds))
              AND (:paysId      IS NULL OR ep.pays_id          = :paysId)
              AND (:status      IS NULL OR ep.lifecycle_status = :status)
              AND (:status      IS NOT NULL OR :includeInactive = 1
                   OR ep.lifecycle_status IN ('ACTIVE', 'ON_LEAVE', 'ON_MISSION'))
              AND (:department  IS NULL OR dept.label_fr       = :department)
              AND (:grade       IS NULL OR g.label_fr          = :grade)
              AND (:contract    IS NULL OR ep.contract_type    = :contract)
              AND (:hireFrom    IS NULL OR ep.hire_date       >= :hireFrom)
              AND (:hireTo      IS NULL OR ep.hire_date       <= :hireTo)
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
              AND u.is_employee = 1
              AND (:scopeAll = 1 OR ep.pays_id IN (:paysIds))
              AND (:paysId      IS NULL OR ep.pays_id          = :paysId)
              AND (:status      IS NULL OR ep.lifecycle_status = :status)
              AND (:status      IS NOT NULL OR :includeInactive = 1
                   OR ep.lifecycle_status IN ('ACTIVE', 'ON_LEAVE', 'ON_MISSION'))
              AND (:department  IS NULL OR dept.label_fr       = :department)
              AND (:grade       IS NULL OR g.label_fr          = :grade)
              AND (:contract    IS NULL OR ep.contract_type    = :contract)
              AND (:hireFrom    IS NULL OR ep.hire_date       >= :hireFrom)
              AND (:hireTo      IS NULL OR ep.hire_date       <= :hireTo)
              AND (:search      IS NULL
                   OR u.fullName LIKE '%' + :search + '%'
                   OR u.email    LIKE '%' + :search + '%')
            """,
           nativeQuery = true)
    Page<EmployeeProfile> search(@Param("paysId")      Long paysId,
                                  @Param("scopeAll")    int scopeAll,
                                  @Param("paysIds")     java.util.List<Long> paysIds,
                                  @Param("status")      String status,
                                  @Param("includeInactive") int includeInactive,
                                  @Param("department")  String department,
                                  @Param("grade")       String grade,
                                  @Param("contract")  String contract,
                                  @Param("hireFrom")  java.time.LocalDate hireFrom,
                                  @Param("hireTo")    java.time.LocalDate hireTo,
                                  @Param("search")    String search,
                                  Pageable pageable);

    java.util.List<EmployeeProfile> findByPaysIdAndDeletedFalse(Long paysId);

    long countByRegimeTemplateIdAndDeletedFalse(Long regimeTemplateId);

    long countByPaysIdAndRegimeTemplateIdNotNullAndDeletedFalse(Long paysId);

    // ── Dashboard queries (admin — unfiltered) ───────────────────────────────

    long countByLifecycleStatus(LifecycleStatus status);

    /**
     * L'effectif : les statuts qui veulent dire « cette personne fait partie de l'équipe ».
     *
     * <p>Remplace {@code countByLifecycleStatus(ACTIVE)} sur la carte effectif. Compter le
     * seul ACTIVE était juste tant que rien n'écrivait ON_MISSION ni ON_LEAVE ; depuis que
     * {@code PresenceStatusJob} les pose, chaque départ en mission faisait baisser
     * l'effectif — quelqu'un en déplacement ou en congé reste membre de l'équipe.
     */
    long countByLifecycleStatusIn(java.util.Collection<LifecycleStatus> statuses);

    long countByOnboardingCompletedTrue();

    @Query("SELECT COUNT(e) FROM EmployeeProfile e WHERE e.onboardingCompleted = false OR e.onboardingCompleted IS NULL")
    long countIncompleted();

    @Query("SELECT e.gender, COUNT(e) FROM EmployeeProfile e WHERE e.lifecycleStatus = :status GROUP BY e.gender")
    List<Object[]> countByGenderAndLifecycleStatus(@Param("status") LifecycleStatus status);

    /** Répartition H/F sur le même ensemble que {@link #countByLifecycleStatusIn}. */
    @Query("""
            SELECT e.gender, COUNT(e) FROM EmployeeProfile e
            WHERE e.lifecycleStatus IN :statuses
            GROUP BY e.gender
            """)
    List<Object[]> countByGenderAndLifecycleStatusIn(
            @Param("statuses") java.util.Collection<LifecycleStatus> statuses);

    @Query("""
            SELECT COUNT(e) FROM EmployeeProfile e
            WHERE e.contractEndDate IS NOT NULL
              AND e.contractEndDate BETWEEN :today AND :limit
              AND e.lifecycleStatus = :status
            """)
    long countContractsEndingSoon(
            @Param("today")  LocalDate today,
            @Param("limit")  LocalDate limit,
            @Param("status") LifecycleStatus status);

    // ── Dashboard queries (tenant-filtered) ──────────────────────────────────

    long countByPaysIdAndLifecycleStatus(Long paysId, LifecycleStatus status);

    long countByPaysIdAndLifecycleStatusIn(
            Long paysId, java.util.Collection<LifecycleStatus> statuses);

    long countByPaysId(Long paysId);

    long countByPaysIdAndOnboardingCompletedTrue(Long paysId);

    @Query("""
            SELECT COUNT(e) FROM EmployeeProfile e
            WHERE e.paysId = :paysId
              AND (e.onboardingCompleted = false OR e.onboardingCompleted IS NULL)
            """)
    long countIncompletedByPaysId(@Param("paysId") Long paysId);

    @Query("""
            SELECT e.gender, COUNT(e) FROM EmployeeProfile e
            WHERE e.lifecycleStatus = :status AND e.paysId = :paysId
            GROUP BY e.gender
            """)
    List<Object[]> countByGenderAndLifecycleStatusAndPaysId(
            @Param("status") LifecycleStatus status,
            @Param("paysId") Long paysId);

    @Query("""
            SELECT e.gender, COUNT(e) FROM EmployeeProfile e
            WHERE e.lifecycleStatus IN :statuses AND e.paysId = :paysId
            GROUP BY e.gender
            """)
    List<Object[]> countByGenderAndLifecycleStatusInAndPaysId(
            @Param("statuses") java.util.Collection<LifecycleStatus> statuses,
            @Param("paysId")   Long paysId);

    @Query("""
            SELECT COUNT(e) FROM EmployeeProfile e
            WHERE e.contractEndDate IS NOT NULL
              AND e.contractEndDate BETWEEN :today AND :limit
              AND e.lifecycleStatus = :status
              AND e.paysId = :paysId
            """)
    long countContractsEndingSoonByPaysId(
            @Param("today")  LocalDate today,
            @Param("limit")  LocalDate limit,
            @Param("status") LifecycleStatus status,
            @Param("paysId") Long paysId);
}
