package com.daf360.rh.payslip;

import com.daf360.rh.service.payslip.PaySlipFileName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Period parsing for payslip file names.
 *
 * <p>The convention is {@code FirstName_LASTNAME_Month_Year.PDF}. The folders do not exist
 * yet, so every case below is the convention as agreed plus the degradations that will show up
 * once HR is filing by hand. Getting a month wrong mislabels somebody's salary, which is why
 * this is pinned rather than left to a live check.
 */
class PaySlipFileNameTest {

    @Test
    void parsesTheAgreedConventionWithAFrenchMonth() {
        PaySlipFileName.Period p = PaySlipFileName.parse("Bilel_ZEDINI_Janvier_2026.PDF", 2026);
        assertThat(p.month()).isEqualTo(1);
        assertThat(p.year()).isEqualTo(2026);
        assertThat(p.followsConvention()).isTrue();
    }

    /** The convention does not say which language or format the month is in, so all three
     *  forms resolve rather than one being picked and the others silently misread. */
    @Test
    void parsesEnglishAndNumericMonthsToo() {
        assertThat(PaySlipFileName.parse("Ali_YASSINE_March_2025.PDF", 2025).month()).isEqualTo(3);
        assertThat(PaySlipFileName.parse("Ali_YASSINE_03_2025.pdf", 2025).month()).isEqualTo(3);
        assertThat(PaySlipFileName.parse("Ali_YASSINE_Aout_2025.PDF", 2025).month()).isEqualTo(8);
        assertThat(PaySlipFileName.parse("Ali_YASSINE_Août_2025.PDF", 2025).month()).isEqualTo(8);
    }

    /** Multi-word surnames are the reason matching is anchored on tokens: "Ali YASSINE BEL HAJ
     *  HOUMA" is a real example of the naming convention. */
    @Test
    void handlesMultiWordSurnames() {
        PaySlipFileName.Period p =
                PaySlipFileName.parse("Ali_YASSINE_BEL_HAJ_HOUMA_Decembre_2026.PDF", 2026);
        assertThat(p.month()).isEqualTo(12);
        assertThat(p.year()).isEqualTo(2026);
    }

    /**
     * A file that does not follow the convention still gets a year from the folder it was
     * found in, and is flagged so the admin panel can list it as worth renaming instead of it
     * silently reading as January.
     */
    @Test
    void fallsBackToTheYearFolderAndFlagsTheFile() {
        PaySlipFileName.Period p = PaySlipFileName.parse("bulletin final (2).pdf", 2024);
        assertThat(p.year()).isEqualTo(2024);
        assertThat(p.month()).isNull();
        assertThat(p.followsConvention()).isFalse();
    }

    /**
     * Tokens, not substrings. A substring search finds "mar" inside a surname and "12" inside
     * a national id, which is how a payslip ends up labelled with a month nobody wrote.
     */
    @Test
    void doesNotFindMonthsInsideNames() {
        // "MARZOUKI" contains "mar"; only a standalone token should count.
        PaySlipFileName.Period p = PaySlipFileName.parse("Sana_MARZOUKI_Juin_2026.PDF", 2026);
        assertThat(p.month()).isEqualTo(6);
    }

    @Test
    void toleratesMissingInputWithoutThrowing() {
        assertThat(PaySlipFileName.parse(null, 2026).year()).isEqualTo(2026);
        assertThat(PaySlipFileName.parse("", null).month()).isNull();
        assertThat(PaySlipFileName.parse("   ", null).year()).isNull();
    }

    // ── namesEmployee: the misfiling check ────────────────────────────────────

    /**
     * Catches a document filed into the wrong employee's folder — the one mistake no path
     * validation can see, because the path is correct and the contents are not.
     */
    @Test
    void namesEmployee_acceptsBothUnderscoreAndSpaceSeparatedSurnames() {
        assertThat(PaySlipFileName.namesEmployee(
                "Ali_YASSINE_BEL_HAJ_HOUMA_Janvier_2026.PDF", "Ali YASSINE BEL HAJ HOUMA")).isTrue();
        assertThat(PaySlipFileName.namesEmployee(
                "Ali_YASSINE BEL HAJ HOUMA_Janvier_2026.PDF", "Ali YASSINE BEL HAJ HOUMA")).isTrue();
    }

    @Test
    void namesEmployee_isAccentAndCaseInsensitive() {
        assertThat(PaySlipFileName.namesEmployee("kods_cherif_Mai_2026.PDF", "Kods CHÉRIF")).isTrue();
    }

    @Test
    void namesEmployee_rejectsAnotherEmployeesFile() {
        assertThat(PaySlipFileName.namesEmployee(
                "Baha_HAMMAMI_Janvier_2026.PDF", "Bilel HAMMAMI")).isFalse();
        assertThat(PaySlipFileName.namesEmployee("Janvier_2026.PDF", "Bilel ZEDINI")).isFalse();
        assertThat(PaySlipFileName.namesEmployee("x.PDF", null)).isFalse();
    }
}
