package com.daf360.rh.repository;

import com.daf360.rh.domain.Nationality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NationalityRepository extends JpaRepository<Nationality, Long> {

    List<Nationality> findByIsActiveTrueOrderByLabelFrAsc();

    Optional<Nationality> findByLabelFr(String labelFr);
}
