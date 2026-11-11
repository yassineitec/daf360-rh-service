package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateCostApproval;
import com.daf360.rh.dto.hiring.SubmitCostApprovalRequest;
import com.daf360.rh.lifecycle.ContractTypeBridge;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.repository.CandidateCostApprovalRepository;
import com.daf360.rh.repository.CandidateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-01.06: submitting a candidate's budget pre-validation twice must not queue two
 * requests in finance — the PENDING one is updated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CandidateCostApprovalServiceTest {

    @Mock CandidateCostApprovalRepository approvalRepo;
    @Mock CandidateRepository candidateRepo;
    @Mock ContractTypeBridge contractTypeBridge;
    @Mock ContractTypeRefs contractTypeRefs;

    private CandidateCostApprovalService service() {
        return new CandidateCostApprovalService(approvalRepo, candidateRepo, contractTypeBridge, contractTypeRefs);
    }

    private SubmitCostApprovalRequest req(String net) {
        SubmitCostApprovalRequest r = new SubmitCostApprovalRequest();
        r.setCandidateId(5L);
        r.setPaysId(179L);
        r.setFiscalYear(2026);
        r.setSalaireNetRh(new BigDecimal(net));
        r.setContractTypeCode("CDI");
        r.setSimulationSnapshot("{\"net\":" + net + "}");
        return r;
    }

    private void candidateExists() {
        when(candidateRepo.findById(5L)).thenReturn(Optional.of(mock(Candidate.class)));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstSubmission_createsOnePendingRequest() {
        candidateExists();
        when(approvalRepo.findByCandidateIdAndJobOfferIdIsNullAndStatusOrderBySubmittedAtDesc(5L, "PENDING"))
                .thenReturn(List.of());

        service().submit(req("1500"), 9L);

        ArgumentCaptor<CandidateCostApproval> saved = ArgumentCaptor.forClass(CandidateCostApproval.class);
        verify(approvalRepo).save(saved.capture());
        assertNull(saved.getValue().getId());
        assertEquals("PENDING", saved.getValue().getStatus());
        assertEquals(0, new BigDecimal("1500").compareTo(saved.getValue().getSalaireNetRh()));
    }

    @Test
    void resubmissionWhilePending_updatesTheSameRequest_insteadOfAddingOne() {
        candidateExists();
        CandidateCostApproval pending = CandidateCostApproval.builder()
                .id(77L).candidateId(5L).status("PENDING")
                .salaireNetRh(new BigDecimal("1500"))
                .submittedBy(1L).submittedAt(OffsetDateTime.now().minusDays(1))
                .build();
        when(approvalRepo.findByCandidateIdAndJobOfferIdIsNullAndStatusOrderBySubmittedAtDesc(5L, "PENDING"))
                .thenReturn(List.of(pending));

        service().submit(req("1700"), 9L);

        ArgumentCaptor<CandidateCostApproval> saved = ArgumentCaptor.forClass(CandidateCostApproval.class);
        verify(approvalRepo).save(saved.capture());
        assertSame(pending, saved.getValue());
        assertEquals(77L, saved.getValue().getId());
        assertEquals(0, new BigDecimal("1700").compareTo(saved.getValue().getSalaireNetRh()));
        assertEquals(9L, saved.getValue().getSubmittedBy());
        assertEquals("{\"net\":1700}", saved.getValue().getSimulationSnapshot());
    }
}
