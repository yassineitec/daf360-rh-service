package com.daf360.rh.repository;

import com.daf360.rh.domain.NogLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NogLevelRepository extends JpaRepository<NogLevel, Long> {

    List<NogLevel> findByPaysIdAndIsActiveTrueOrderByLevelOrderAsc(Long paysId);
}
