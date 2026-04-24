package com.daf360.rh.scheduler;

import com.daf360.rh.repository.ContractRepository;
import com.daf360.rh.repository.PaySlipRepository;
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
    private final PaySlipRepository paySlipRepository;

    /** BR08: check for unpublished pay slips and alert HR */
    @Scheduled(cron = "0 0 8 * * MON-FRI")
    public void checkUnpublishedPaySlips() {
        LocalDate now = LocalDate.now();
        var unpublished = paySlipRepository
                .findByPublishedFalseAndMonthPeriodAndYearPeriod(now.getMonthValue(), now.getYear());
        if (!unpublished.isEmpty()) {
            log.warn("BR08: {} pay slip(s) unpublished for {}/{}",
                     unpublished.size(), now.getMonthValue(), now.getYear());
        }
    }

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
