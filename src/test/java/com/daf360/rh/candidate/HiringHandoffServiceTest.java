package com.daf360.rh.candidate;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import com.daf360.rh.service.HiringHandoffService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** End of the offer step: onboarding and IT provisioning open together. */
@ExtendWith(MockitoExtension.class)
class HiringHandoffServiceTest {

    @Mock ItProvisioningRepository   itProvRepo;
    @Mock CandidateRepository        candidateRepo;
    @Mock NotificationRoutingService notificationRoutingService;

    @InjectMocks HiringHandoffService service;

    private Candidate offerSent() {
        return Candidate.builder().id(10L).firstName("Alice").lastName("Martin")
                .paysId(1L).status(CandidateStatus.OFFER_SENT).build();
    }

    @Test
    void opensItFileAndOnboardingAndTellsItAndHr() {
        Candidate c = offerSent();
        when(itProvRepo.findByCandidateId(10L)).thenReturn(Optional.empty());

        service.startOnboardingAndIt(c);

        ArgumentCaptor<ItProvisioning> prov = ArgumentCaptor.forClass(ItProvisioning.class);
        verify(itProvRepo).save(prov.capture());
        assertThat(prov.getValue().getCandidateId()).isEqualTo(10L);
        assertThat(prov.getValue().getStatus()).isEqualTo(ItProvisioningStatus.PENDING);

        // In the onboarding list at once — not after IT.
        assertThat(c.getStatus()).isEqualTo(CandidateStatus.HR_IN_PROGRESS);
        verify(candidateRepo).save(c);

        ArgumentCaptor<RoutingContext> ctx = ArgumentCaptor.forClass(RoutingContext.class);
        verify(notificationRoutingService).resolveAndDispatch(ctx.capture());
        assertThat(ctx.getValue().getEventCode()).isEqualTo("CANDIDATE_ACCEPTED");
        assertThat(ctx.getValue().getEntityType()).isEqualTo(NotificationEntityType.CANDIDATE);
        assertThat(ctx.getValue().getEntityId()).isEqualTo(10L);
    }

    @Test
    void reusesTheItFileOfTheOldFlow() {
        Candidate c = offerSent();
        when(itProvRepo.findByCandidateId(10L))
                .thenReturn(Optional.of(ItProvisioning.builder().id(5L).candidateId(10L).build()));

        service.startOnboardingAndIt(c);

        verify(itProvRepo, never()).save(any());
        assertThat(c.getStatus()).isEqualTo(CandidateStatus.HR_IN_PROGRESS);
    }
}
