package com.daf360.rh.repository;

import com.daf360.rh.domain.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BankRepository extends JpaRepository<Bank, Long> {

    List<Bank> findByPaysIdAndIsActiveTrueOrderByLabelFrAsc(Long paysId);
    List<Bank> findByIsActiveTrueOrderByLabelFrAsc();

    /** Several entities at once — see the note on GradeRepository. */
    List<Bank> findByPaysIdInAndIsActiveTrueOrderByLabelFrAsc(Collection<Long> paysIds);
}
