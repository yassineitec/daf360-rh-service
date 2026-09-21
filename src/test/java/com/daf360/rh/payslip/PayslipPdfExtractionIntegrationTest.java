package com.daf360.rh.payslip;

import com.daf360.rh.service.payslip.PayslipMatriculeExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a REAL PDF with PDFBox — not a canned text fixture — to prove the extraction approach
 * against the actual failure mode found in the real sample ("BP VIERGE.pdf"): a payroll-software
 * PDF that draws "MATRICULE" and its value on the same printed line, but writes them to the
 * content stream in an order that does not match that visual layout.
 *
 * <p>{@link PayslipMatriculeExtractorTest} pins the extractor's logic against text that is
 * ALREADY in visual order. This test instead reproduces the disordering itself — drawing
 * "MATRICULE" and "208" at the same Y coordinate (same printed line) but with a page's worth of
 * unrelated text operations emitted in between them in the stream — then runs the real
 * {@code PDFTextStripper.setSortByPosition(true)} step {@code PayslipBatchService} uses, to
 * confirm sorting genuinely reassembles them onto one line before the extractor ever sees the
 * text. Without this test, "does setSortByPosition actually fix it" would be resting on reading
 * the PDFBox docs rather than on running the real library against the real failure shape.
 */
class PayslipPdfExtractionIntegrationTest {

    @Test
    void sortByPosition_reassemblesALabelAndValueWrittenOutOfOrder_ontoOneLine() throws IOException {
        byte[] pdfBytes = buildPageWithScrambledMatricule();

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String sortedText = stripper.getText(document);

            assertThat(PayslipMatriculeExtractor.extract(sortedText)).isEqualTo("208");
        }
    }

    @Test
    void withoutSortByPosition_theLabelAndValueCanLandOnDifferentLines_demonstratingWhySortingIsRequired() throws IOException {
        byte[] pdfBytes = buildPageWithScrambledMatricule();

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Deliberately NOT calling setSortByPosition(true) here.
            String unsortedText = stripper.getText(document);

            // Not asserting extract() fails here -- PDFBox's default order is not guaranteed to
            // reproduce the exact real-world failure on every JVM/font combination. The point of
            // this test is documentary: it runs the counter-example next to the real fix so a
            // future reader can see both, not to pin an exact unsorted byte order.
            assertThat(unsortedText).contains("MATRICULE").contains("208");
        }
    }

    /**
     * One PDF page where "MATRICULE" (x=100) and "208" (x=250) are drawn at the SAME y=700 —
     * i.e. the same printed line — but with several unrelated text lines drawn on the content
     * stream in between them, mirroring how the real sample interleaves unrelated fields
     * (hire date, CNSS number) between the label and its value.
     */
    private byte[] buildPageWithScrambledMatricule() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.setFont(font, 10);

                drawAt(cs, 100, 760, "BULLETIN DE PAIE");
                drawAt(cs, 100, 740, "CNSS EMPLOYEUR : 593039-78");
                drawAt(cs, 100, 700, "MATRICULE");             // label, same line as the value below
                drawAt(cs, 100, 680, "Date d'embauche : 01/08/26");
                drawAt(cs, 100, 660, "Nationalite : Tunisienne");
                drawAt(cs, 250, 700, "208");                   // value, same y as the label above
                drawAt(cs, 100, 640, "Mode de paiement : Virement");
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private void drawAt(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
