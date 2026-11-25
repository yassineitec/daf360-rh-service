package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The hand-off at the end of the offer step: both parties agreed on the salary, so the
 * candidate is ACCEPTED for good and leaves the candidate / negotiation workflow.
 *
 * <p>Two tracks open AT THE SAME TIME, instead of IT first and HR after:
 * <ul>
 *   <li><b>IT provisioning</b> — the file is opened here (it used to be opened when RH validated
 *       the candidature, before any offer, so IT saw candidates who were still negotiating);</li>
 *   <li><b>onboarding</b> — the candidate goes to HR_IN_PROGRESS and appears in the onboarding
 *       list at once. RH fills the wizard while IT works; only the final « Créer le profil »
 *       waits for the Microsoft 365 account IT creates (OnboardingService checks it).</li>
 * </ul>
 * The CANDIDATE_ACCEPTED routing event (IT + HR) moves here with the IT file it announces.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HiringHandoffService {

    private final ItProvisioningRepository   itProvRepo;
    private final CandidateRepository        candidateRepo;
    private final NotificationRoutingService notificationRoutingService;
    private final DemandFillService          demandFillService;

    /** Opens IT provisioning and onboarding for a candidate whose offer is agreed. Idempotent. */
    public void startOnboardingAndIt(Candidate candidate) {
        OffsetDateTime now = OffsetDateTime.now();

        // A candidature validated under the old flow already has its IT file: reuse it.
        if (itProvRepo.findByCandidateId(candidate.getId()).isEmpty()) {
            itProvRepo.save(ItProvisioning.builder()
                    .candidateId(candidate.getId())
                    .status(ItProvisioningStatus.PENDING)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        candidate.setStatus(CandidateStatus.HR_IN_PROGRESS);
        candidate.setUpdatedAt(now);
        candidateRepo.save(candidate);

        // The post is now taken (V137): the demand may be full — close it and archive the
        // candidatures still competing for it.
        demandFillService.recomputeFor(candidate.getRecruitmentDemandId(), null);

        try {
            notificationRoutingService.resolveAndDispatch(
                    RoutingContext.builder()
                            .eventCode("CANDIDATE_ACCEPTED")
                            .paysId(candidate.getPaysId())
                            .entityType(NotificationEntityType.CANDIDATE)
                            .entityId(candidate.getId())
                            .templateVars(Map.of(
                                    "candidateName", candidate.getFirstName() + " " + candidate.getLastName(),
                                    "firstName",     candidate.getFirstName(),
                                    "lastName",      candidate.getLastName()))
                            .build());
        } catch (Exception ex) {
            // The hand-off itself must not fail over a notification.
            log.warn("CANDIDATE_ACCEPTED notification failed for candidate {}: {}",
                    candidate.getId(), ex.getMessage());
        }
    }
}
