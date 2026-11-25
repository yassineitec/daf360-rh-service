package com.daf360.rh.repository;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    // No findByEmailPersonal returning Optional any more: since V137 one e-mail may carry
    // several candidatures (an earlier one archived or rejected), and an Optional finder would
    // throw on the second row.

    boolean existsByEmailPersonal(String emailPersonal);

    /**
     * An e-mail is refused for a new candidature only while one of its candidatures is still in
     * progress (V137) — an archived, rejected or hired one does not block a new application.
     */
    boolean existsByEmailPersonalIgnoreCaseAndStatusIn(String emailPersonal, Collection<CandidateStatus> statuses);

    boolean existsByEmailPersonalIgnoreCaseAndStatusInAndIdNot(String emailPersonal,
                                                               Collection<CandidateStatus> statuses, Long id);

    /** The candidatures of one demand in the given statuses — the ones archived when it fills. */
    List<Candidate> findByRecruitmentDemandIdAndStatusIn(Long recruitmentDemandId, Collection<CandidateStatus> statuses);

    List<Candidate> findByStatusIn(Collection<CandidateStatus> statuses);

    List<Candidate> findByStatusInAndPaysId(Collection<CandidateStatus> statuses, Long paysId);

    long countByStatusIn(Collection<CandidateStatus> statuses);

    long countByStatusInAndPaysId(Collection<CandidateStatus> statuses, Long paysId);

    /** Plusieurs pays (drapeaux de la navbar) — voir OnboardingService.getPendingList. */
    List<Candidate> findByStatusInAndPaysIdIn(Collection<CandidateStatus> statuses, Collection<Long> paysIds);

    @Query("""
        SELECT c FROM Candidate c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:paysId IS NULL OR c.paysId = :paysId)
          AND (:search IS NULL
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.createdAt DESC
        """)
    List<Candidate> search(
            @Param("status")  CandidateStatus status,
            @Param("paysId")  Long paysId,
            @Param("search")  String search);

    /**
     * Paged list behind the recruitment board / list. Every filter is optional (null = not
     * applied). {@code spontaneousOnly} keeps only candidatures answering no recruitment
     * demand; {@code createdFrom} is inclusive, {@code createdTo} exclusive.
     */
    @Query(value = """
        SELECT c FROM Candidate c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:allPays = true OR c.paysId IN :paysIds)
          AND (:search IS NULL
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        """,
        countQuery = """
        SELECT COUNT(c) FROM Candidate c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:allPays = true OR c.paysId IN :paysIds)
          AND (:search IS NULL
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        """)
    Page<Candidate> searchPaged(
            @Param("status")          CandidateStatus status,
            @Param("allPays")         boolean allPays,
            @Param("paysIds")         Collection<Long> paysIds,
            @Param("search")          String search,
            @Param("departmentId")    Long departmentId,
            @Param("demandId")        Long demandId,
            @Param("spontaneousOnly") boolean spontaneousOnly,
            @Param("createdFrom")     OffsetDateTime createdFrom,
            @Param("createdTo")       OffsetDateTime createdTo,
            Pageable pageable);

    /** Same filters as {@link #searchPaged}, for a pipeline stage (a set of statuses). */
    @Query(value = """
        SELECT c FROM Candidate c
        WHERE c.status IN :statuses
          AND (:allPays = true OR c.paysId IN :paysIds)
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal)   LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        """,
        countQuery = """
        SELECT COUNT(c) FROM Candidate c
        WHERE c.status IN :statuses
          AND (:allPays = true OR c.paysId IN :paysIds)
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal)   LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        """)
    Page<Candidate> searchByStatusesAndSearch(
            @Param("statuses")        Collection<CandidateStatus> statuses,
            @Param("allPays")         boolean allPays,
            @Param("paysIds")         Collection<Long> paysIds,
            @Param("search")          String search,
            @Param("departmentId")    Long departmentId,
            @Param("demandId")        Long demandId,
            @Param("spontaneousOnly") boolean spontaneousOnly,
            @Param("createdFrom")     OffsetDateTime createdFrom,
            @Param("createdTo")       OffsetDateTime createdTo,
            Pageable pageable);
}
