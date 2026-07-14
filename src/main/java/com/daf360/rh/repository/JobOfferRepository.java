package com.daf360.rh.repository;

import com.daf360.rh.domain.JobOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {

    Optional<JobOffer> findByCandidateId(Long candidateId);

    boolean existsByCandidateId(Long candidateId);
}
