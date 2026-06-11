package com.daf360.rh.repository;

import com.daf360.rh.domain.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankRepository extends JpaRepository<Bank, Long> {

    List<Bank> findByPaysIdAndIsActiveTrueOrderByLabelFrAsc(Long paysId);
}
