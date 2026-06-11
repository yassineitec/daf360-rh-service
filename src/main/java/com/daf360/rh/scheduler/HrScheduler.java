package com.daf360.rh.scheduler;

import com.daf360.rh.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class HrScheduler {

    private final ContractRepository contractRepository;

    /** Daily check: alert on contracts expiring within 30 days */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void checkExpiringContracts() {
        LocalDate threshold = LocalDate.now().plusDays(30);
        var expiring = contractRepository.findByEndDateBeforeAndIsActiveTrue(threshold);
        if (!expiring.isEmpty()) {
            log.info("Contracts expiring within 30 days: {}", expiring.size());
        }
    }
}
