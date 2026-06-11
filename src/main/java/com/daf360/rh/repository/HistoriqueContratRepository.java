package com.daf360.rh.repository;

import com.daf360.rh.domain.HistoriqueContrat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface HistoriqueContratRepository extends JpaRepository<HistoriqueContrat, Long> {

    /** All contracts for an employee, ordered chronologically descending */
    List<HistoriqueContrat> findByIdCollaborateurOrderByDateEffetDesc(Long idCollaborateur);

    /** Find open (no date_fin) contract for auto-close on new avenant */
    @Query("""
        SELECT h FROM HistoriqueContrat h
        WHERE h.idCollaborateur = :profileId
          AND h.dateFin IS NULL
        ORDER BY h.dateEffet DESC
        """)
    List<HistoriqueContrat> findOpenContracts(@Param("profileId") Long profileId);

    /** Active contract at a given date */
    @Query("""
        SELECT h FROM HistoriqueContrat h
        WHERE h.idCollaborateur = :profileId
          AND h.dateEffet <= :date
          AND (h.dateFin IS NULL OR h.dateFin >= :date)
        ORDER BY h.dateEffet DESC
        """)
    List<HistoriqueContrat> findActiveAtDate(
            @Param("profileId") Long profileId,
            @Param("date") LocalDate date);
}
