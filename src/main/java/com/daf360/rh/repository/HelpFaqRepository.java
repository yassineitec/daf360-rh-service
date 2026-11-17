package com.daf360.rh.repository;

import com.daf360.rh.domain.HelpFaq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HelpFaqRepository extends JpaRepository<HelpFaq, Long> {

    List<HelpFaq> findAllByOrderByPageKeyAscOrderIndexAscIdAsc();

    List<HelpFaq> findByIsActiveTrueOrderByPageKeyAscOrderIndexAscIdAsc();
}
