package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.lifecycle.CreateContractRequest;
import com.daf360.rh.lifecycle.EmployeeLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three side-effects of {@code OnboardingService.completeEmployeeProfile} that are
 * allowed to fail without failing the onboarding — each in its OWN transaction.
 *
 * <p><b>Why this bean exists.</b> {@code OnboardingService} is {@code @Transactional}, and it
 * wrapped these three calls in {@code try/catch} to make them non-fatal. But every callee is
 * itself {@code @Transactional} with the default {@code REQUIRED}, so each JOINED the
 * onboarding's transaction. When one threw, Spring marked that shared transaction
 * {@code rollbackOnly} before the exception ever reached the {@code catch}; the catch then
 * swallowed it, the method ran happily to its {@code return}, and the commit blew up with
 * {@code UnexpectedRollbackException}. The whole onboarding rolled back — no profile, the
 * candidate never HIRED — and the caller got a 500 with nothing usable in it. The three
 * "non-fatal" steps were, in practice, the most fatal thing on the page.
 *
 * <p>{@code REQUIRES_NEW} runs each side-effect in a transaction of its own, so a failure rolls
 * back only that side-effect's own writes and the catch here is truthful.
 *
 * <p><b>These are called after the onboarding has COMMITTED</b> — see the {@code afterCommit}
 * block in {@code completeEmployeeProfile}. Propagation alone is not enough, and getting this
 * wrong is silent rather than loud: all three read the employee profile the onboarding has just
 * written ({@code doCreateContract} opens with {@code findById} and throws "Collaborateur
 * introuvable", {@code seedFromProvisioning} looks the profile up by candidate id). A
 * {@code REQUIRES_NEW} transaction started from INSIDE the onboarding suspends it while it is
 * still uncommitted, so that row is invisible and every one of these turns into a failure or a
 * no-op. {@code REQUIRES_NEW} is kept anyway: after commit there is no transaction to join, and
 * being explicit means a future caller inside one still gets its own.
 *
 * <p><b>Why not annotate the callees.</b> {@code EmployeeLifecycleService},
 * {@code EmployeeDocumentService} and {@code ItAssetAssignmentService} have other callers for
 * which the work IS atomic with the caller — {@code CandidateService.hireCandidate} must not
 * commit a hire whose contract failed. The propagation belongs to this call path, not to those
 * services, so it is declared here.
 *
 * <p>Each method returns {@code true} on success and logs the full throwable on failure. The
 * throwable, not {@code ex.getMessage()}: the message alone is what made the original incident
 * impossible to diagnose from the logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingSideEffects {

    private final EmployeeLifecycleService lifecycleService;
    private final EmployeeDocumentService  documentService;
    private final ItAssetAssignmentService assetAssignmentService;

    /**
     * The lifecycle contract for a freshly onboarded employee.
     *
     * A failure leaves a complete profile with no contract row, which is recoverable: the
     * Contrats tab of {@code /rh/profiles/:id} can create one. Losing the whole onboarding to
     * it is not.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean createContract(CreateContractRequest req, EmployeeProfile profile,
                                  Long hrOfficerId) {
        try {
            var created = lifecycleService.createContractFromBridge(req, hrOfficerId);
            log.info("Onboarding created contract {} for profile {} — préavis {} j",
                    created.getId(), profile.getId(), created.getNoticePeriodDays());
            return true;
        } catch (Exception ex) {
            log.warn("Could not create the lifecycle contract for profile {} during onboarding"
                     + " — the profile is complete but has no contract row.", profile.getId(), ex);
            return false;
        }
    }

    /** Turns the contract PDF staged against the candidate into a document on the profile. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean linkContractDocument(Long profileId, String url, String name, Long hrOfficerId) {
        try {
            documentService.registerStagedDocument(profileId, url, name, "CONTRACT_SIGNED", hrOfficerId);
            return true;
        } catch (Exception ex) {
            // The file is on disk either way; losing the row is recoverable by re-uploading.
            log.warn("Could not attach the signed contract to profile {}", profileId, ex);
            return false;
        }
    }

    /**
     * Opens the IT equipment ledger from what provisioning marked as handed over (V76).
     *
     * Idempotent on (provisioning, asset type), so running it here and from
     * {@code ItProvisioningService.completeProvisioning} is safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean seedItAssets(Long provisioningId, Long candidateId, Long hrOfficerId) {
        try {
            assetAssignmentService.seedFromProvisioning(provisioningId, hrOfficerId);
            return true;
        } catch (Exception ex) {
            log.warn("IT asset ledger seeding failed for candidateId={}", candidateId, ex);
            return false;
        }
    }
}
