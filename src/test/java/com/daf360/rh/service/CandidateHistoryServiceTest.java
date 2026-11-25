package com.daf360.rh.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How the action log's "k=v; k=v" lines become the history's status and details. */
class CandidateHistoryServiceTest {

    @Test
    void parsesKeyValueLinesAndDropsEmptyValues() {
        Map<String, String> facts = CandidateHistoryService.parseFacts(
                "round=2; proposedSalary=3100.0000; proposedBy=RH; noticePeriodDays=null; status=DRAFT");

        assertThat(facts).containsEntry("round", "2")
                .containsEntry("proposedSalary", "3100.0000")
                .containsEntry("proposedBy", "RH")
                .containsEntry("status", "DRAFT")
                .doesNotContainKey("noticePeriodDays");
    }

    @Test
    void keepsTheReasonWholeEvenWithSpaces() {
        Map<String, String> facts = CandidateHistoryService.parseFacts("status=REJECTED; reason=Salaire trop élevé");

        assertThat(facts).containsEntry("reason", "Salaire trop élevé");
    }

    @Test
    void statusIsTheCodeWithoutItsComment() {
        assertThat(CandidateHistoryService.statusToken("HR_IN_PROGRESS (onboarding + IT)")).isEqualTo("HR_IN_PROGRESS");
        assertThat(CandidateHistoryService.statusToken("HIRED")).isEqualTo("HIRED");
        assertThat(CandidateHistoryService.statusToken(null)).isNull();
    }

    @Test
    void emptyOrMissingLineGivesNoFacts() {
        assertThat(CandidateHistoryService.parseFacts(null)).isEmpty();
        assertThat(CandidateHistoryService.parseFacts("  ")).isEmpty();
        assertThat(CandidateHistoryService.parseFacts("cv")).isEmpty();
    }
}
