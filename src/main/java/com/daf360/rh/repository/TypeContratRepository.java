package com.daf360.rh.repository;

import com.daf360.rh.domain.TypeContrat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TypeContratRepository extends JpaRepository<TypeContrat, Long> {
    List<TypeContrat> findByIsActiveTrueOrderByLabelFrAsc();
    Optional<TypeContrat> findByCode(String code);
}
