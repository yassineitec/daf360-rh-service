package com.daf360.rh.payslip;

import com.daf360.rh.service.payslip.PayslipMatriculeExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matricule extraction from one page's position-sorted text.
 *
 * <p>The fixtures below mirror the real sample this was built against ("BP VIERGE.pdf", a
 * Tunisian ARX payslip export): the printed page shows "MATRICULE   208" together on one line,
 * boxed near the top, with no punctuation between label and value — but the PDF's raw
 * content-stream order scatters "MATRICULE" and "208" many tokens apart, interleaved with
 * unrelated fields (date of hire, CNSS number, pay lines). {@link PayslipMatriculeExtractor}
 * assumes its input has ALREADY been reordered into visual reading order
 * ({@code PDFTextStripper.setSortByPosition(true)}); these tests exercise the extractor on text
 * shaped the way that reordering is expected to produce it, not on the raw scattered order.
 */
class PayslipMatriculeExtractorTest {

    @Test
    void extractsTheRealSampleFormat_labelAndValueOnOneLine_noPunctuation() {
        String pageText = """
                07 Avenue de Paris
                Boumhal - Ben Arous
                MATRICULE   208
                M
                Date d'embauche : 01/08/26
                """;
        assertThat(PayslipMatriculeExtractor.extract(pageText)).isEqualTo("208");
    }

    @Test
    void toleratesAColonOrEqualsSeparator_otherPayrollSoftwareFormats() {
        assertThat(PayslipMatriculeExtractor.extract("Matricule : 00123")).isEqualTo("00123");
        assertThat(PayslipMatriculeExtractor.extract("Matricule = 00123")).isEqualTo("00123");
        assertThat(PayslipMatriculeExtractor.extract("N° Matricule : 00123")).isEqualTo("00123");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(PayslipMatriculeExtractor.extract("matricule 208")).isEqualTo("208");
        assertThat(PayslipMatriculeExtractor.extract("MaTrIcUlE 208")).isEqualTo("208");
    }

    @Test
    void fallsBackToTheNextLineWhenTheLabelHasNoValueAttached() {
        String pageText = """
                Nationalite : Tunisienne
                MATRICULE
                208
                Mode de paiement : Virement
                """;
        assertThat(PayslipMatriculeExtractor.extract(pageText)).isEqualTo("208");
    }

    @Test
    void doesNotLatchOntoUnrelatedNumbersElsewhereOnThePage() {
        // A page with plenty of OTHER numbers is exactly where a whole-text (rather than
        // line-anchored) search could grab the wrong one -- N°CNSS, dates, pay amounts.
        String pageText = """
                CNSS EMPLOYEUR : 593039-78
                N°CNSS: 1
                Date d'embauche : 01/08/26
                MATRICULE   208
                Total Brut 39,192
                """;
        assertThat(PayslipMatriculeExtractor.extract(pageText)).isEqualTo("208");
    }

    @Test
    void returnsNullWhenNoMatriculeLabelIsPresent() {
        assertThat(PayslipMatriculeExtractor.extract("bulletin final (2).pdf")).isNull();
    }

    @Test
    void returnsNullOnTheLabelWithNoRecognisableValueNearby() {
        String pageText = """
                MATRICULE
                Date d'embauche : 01/08/26
                """;
        assertThat(PayslipMatriculeExtractor.extract(pageText)).isNull();
    }

    @Test
    void toleratesMissingInputWithoutThrowing() {
        assertThat(PayslipMatriculeExtractor.extract(null)).isNull();
        assertThat(PayslipMatriculeExtractor.extract("")).isNull();
        assertThat(PayslipMatriculeExtractor.extract("   ")).isNull();
    }

    @Test
    void handlesAnAlphanumericMatricule() {
        assertThat(PayslipMatriculeExtractor.extract("MATRICULE MAT-00123")).isEqualTo("MAT-00123");
    }
}
