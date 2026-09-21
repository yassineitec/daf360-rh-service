package com.daf360.rh.service.payslip;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Matricule" value off one page of a payslip PDF's extracted text.
 *
 * <p>Pure text-in, string-out — no PDFBox, no DB — for the same reason {@link PaySlipFileName}
 * is: getting this wrong silently shows one employee's page to another, so the logic has to be
 * cheap to pin exactly in tests.
 */
public final class PayslipMatriculeExtractor {

    private PayslipMatriculeExtractor() {}

    /** Optional "N°"/"No"/":"/"=" between the label and the value — the one real sample seen
     * so far ("BP VIERGE.pdf", a Tunisian payroll export) has none of these, just whitespace,
     * but other payroll software may punctuate it. */
    private static final Pattern MATRICULE_PATTERN = Pattern.compile(
            "MATRICULE\\s*(?:N[°o]\\s*)?[:=]?\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)",
            Pattern.CASE_INSENSITIVE);

    /** A bare value on its own line — used only as the fallback below. */
    private static final Pattern BARE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-]{0,19}");

    /**
     * @param sortedPageText text of ONE page, extracted with
     *                       {@code PDFTextStripper.setSortByPosition(true)}.
     *
     *                       <p>Position-sorting is required, not optional: on the real sample
     *                       this was built against, "MATRICULE" and its value ("208") sit
     *                       together on one printed line but are dozens of tokens apart in the
     *                       PDF's raw content-stream order — the payroll software does not write
     *                       text objects in reading order. Without sorting, the two could end up
     *                       separated by unrelated page content and either miss entirely or,
     *                       worse, latch onto some other number on the page.
     * @return the matricule value, or null if the label was not found or had no usable value
     *         next to it. Never guesses: a near-miss returns null rather than a wrong employee.
     */
    public static String extract(String sortedPageText) {
        if (sortedPageText == null || sortedPageText.isBlank()) return null;

        String[] lines = sortedPageText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.toUpperCase(Locale.ROOT).contains("MATRICULE")) continue;

            Matcher m = MATRICULE_PATTERN.matcher(line);
            if (m.find()) {
                String value = m.group(1).trim();
                if (!value.isEmpty()) return value;
            }

            // The label matched this line but not with a value attached — try the very next
            // line before giving up on this occurrence. Covers a slightly different line-break
            // outcome than the sample this was tuned against, without weakening the same-line
            // match above (which stays the primary, more reliable path).
            if (i + 1 < lines.length) {
                String candidate = lines[i + 1].trim();
                if (BARE_TOKEN.matcher(candidate).matches()) return candidate;
            }
        }
        return null;
    }
}
