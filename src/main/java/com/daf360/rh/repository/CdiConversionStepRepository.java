package com.daf360.rh.repository;

import com.daf360.rh.domain.CdiConversionStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CdiConversionStepRepository extends JpaRepository<CdiConversionStep, Long> {

    List<CdiConversionStep> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);
}
