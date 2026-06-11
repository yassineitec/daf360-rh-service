package com.daf360.rh.repository;

import com.daf360.rh.domain.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByPaysId(Long paysId);

    List<Holiday> findByPaysIdAndDateHolidayBetween(Long paysId, LocalDate from, LocalDate to);

    Optional<Holiday> findByPaysIdAndDateHoliday(Long paysId, LocalDate date);

    boolean existsByPaysIdAndDateHoliday(Long paysId, LocalDate date);
}
