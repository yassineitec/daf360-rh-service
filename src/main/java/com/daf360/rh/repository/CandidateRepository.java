package com.daf360.rh.repository;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.enums.CandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    long countByStatusIn(Collection<CandidateStatus> statuses);

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

    @Query(value = """
        SELECT c FROM Candidate c
        WHERE (:status IS NULL OR c.status = :status)
          AND (:paysId IS NULL OR c.paysId = :paysId)
          AND (:search IS NULL
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.emailPersonal) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
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
        """)
    Page<Candidate> searchPaged(
            @Param("status")  CandidateStatus status,
            @Param("paysId")  Long paysId,
            @Param("search")  String search,
            Pageable pageable);

    @Query(value = """
        SELECT c FROM Candidate c
        WHERE c.status IN :statuses
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(c) FROM Candidate c
        WHERE c.status IN :statuses
          AND (:search IS NULL
               OR LOWER(c.firstName)       LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.lastName)        LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.appliedPosition) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Candidate> searchByStatusesAndSearch(
            @Param("statuses") Collection<CandidateStatus> statuses,
            @Param("search")   String search,
            Pageable pageable);
}
