package com.daf360.rh.repository;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.domain.enums.DemandeEtat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutorisationRepository extends JpaRepository<Autorisation, Long> {

    Page<Autorisation> findByCollaborateurIdOrderByDateAutorisationDesc(
            Long collaborateurId, Pageable pageable);

    Page<Autorisation> findByCollaborateurIdAndEtatDemande(
            Long collaborateurId, DemandeEtat etat, Pageable pageable);
}
