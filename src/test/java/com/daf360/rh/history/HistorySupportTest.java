package com.daf360.rh.history;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading the action log's free-form values, and the period window. */
class HistorySupportTest {

    @Test
    void readsSpaceSeparatedValuesThatContainSpaces() {
        var f = HistorySupport.facts("group=RH_KIT label=Attestation de travail");
        assertThat(f).containsEntry("group", "RH_KIT").containsEntry("label", "Attestation de travail");
    }

    @Test
    void readsSemicolonAndCommaSeparatedValues() {
        assertThat(HistorySupport.facts("round=2; proposedSalary=3100"))
                .containsEntry("round", "2").containsEntry("proposedSalary", "3100");
        assertThat(HistorySupport.facts("contract=5, salary=2400.000"))
                .containsEntry("contract", "5").containsEntry("salary", "2400.000");
    }

    @Test
    void keepsLeadingTextAndDropsNulls() {
        var f = HistorySupport.facts("IN_PROGRESS | reason=Erreur de saisie");
        assertThat(f).containsEntry("text", "IN_PROGRESS").containsEntry("reason", "Erreur de saisie");
        assertThat(HistorySupport.facts("lastWorkingDay=null")).isEmpty();
        assertThat(HistorySupport.facts("ARCHIVED")).containsEntry("text", "ARCHIVED");
        assertThat(HistorySupport.facts(null)).isEmpty();
    }

    @Test
    void windowIsWidenedByADayOnEachSide() {
        var w = HistorySupport.window(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12));
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 11, 9).atStartOfDay());
        assertThat(w.to()).isEqualTo(LocalDate.of(2026, 11, 14).atStartOfDay());
        assertThat(w.contains(LocalDate.of(2026, 11, 13).atTime(23, 59))).isTrue();
        assertThat(w.contains(LocalDate.of(2026, 11, 14).atStartOfDay())).isFalse();
    }
}
