package com.daf360.rh.repository;

import com.daf360.rh.domain.Teletravail;
import com.daf360.rh.domain.enums.DemandeEtat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface TeletravailRepository extends JpaRepository<Teletravail, Long> {

    Page<Teletravail> findByCollaborateurIdOrderByDateTeletravailDesc(Long collaborateurId, Pageable pageable);

    Page<Teletravail> findByCollaborateurIdAndEtatDemande(
            Long collaborateurId, DemandeEtat etat, Pageable pageable);

    /** Overlap check — any EN_ATTENTE or VALIDE teletravail on the same day. */
    @Query("""
            SELECT t FROM Teletravail t
            WHERE t.collaborateurId = :userId
              AND t.etatDemande NOT IN ('REFUSE', 'ARCHIVE')
              AND t.dateTeletravail >= :from
              AND t.dateTeletravail <= :to
            """)
    List<Teletravail> findOverlapping(@Param("userId") Long userId,
                                       @Param("from")   OffsetDateTime from,
                                       @Param("to")     OffsetDateTime to);
}
