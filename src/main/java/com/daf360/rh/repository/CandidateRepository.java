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

    Optional<Candidate> findByEmailPersonal(String emailPersonal);

    boolean existsByEmailPersonal(String emailPersonal);

    List<Candidate> findByStatusIn(Collection<CandidateStatus> statuses);

    List<Candidate> findByStatusInAndPaysId(Collection<CandidateStatus> statuses, Long paysId);

    long countByStatusIn(Collection<CandidateStatus> statuses);

    long countByStatusInAndPaysId(Collection<CandidateStatus> statuses, Long paysId);

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
          AND (:paysId IS NULL OR c.paysId = :paysId)
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
          AND (:paysId IS NULL OR c.paysId = :paysId)
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
            @Param("paysId")          Long paysId,
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
          AND (:paysId IS NULL OR c.paysId = :paysId)
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        ORDER BY c.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM Candidate c
        WHERE c.status IN :statuses
          AND (:paysId IS NULL OR c.paysId = :paysId)
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:departmentId IS NULL OR c.department.id = :departmentId)
          AND (:demandId IS NULL OR c.recruitmentDemandId = :demandId)
          AND (:spontaneousOnly = false OR c.recruitmentDemandId IS NULL)
          AND (:createdFrom IS NULL OR c.createdAt >= :createdFrom)
          AND (:createdTo IS NULL OR c.createdAt < :createdTo)
        """)
    Page<Candidate> searchByStatusesAndSearch(
            @Param("statuses")        Collection<CandidateStatus> statuses,
            @Param("paysId")          Long paysId,
            @Param("search")          String search,
            @Param("departmentId")    Long departmentId,
            @Param("demandId")        Long demandId,
            @Param("spontaneousOnly") boolean spontaneousOnly,
            @Param("createdFrom")     OffsetDateTime createdFrom,
            @Param("createdTo")       OffsetDateTime createdTo,
            Pageable pageable);
}
