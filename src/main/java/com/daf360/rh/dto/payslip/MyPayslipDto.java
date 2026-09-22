package com.daf360.rh.dto.payslip;

/**
 * One payslip an employee can download from their own document history.
 *
 * <p>The period is carried as NUMBERS, not as a formatted label: the frontend renders
 * "Mars 2026" or "March 2026" from the active language, and a label built here would arrive
 * in whatever locale the server happened to be in. {@code fileName} is the display fallback
 * for the case where nothing in the name could be parsed — see
 * {@code PaySlipFileName}, which degrades in steps rather than failing.
 *
 * @param fileName          the SharePoint file name, extension included. Also the handle the
 *                          download endpoint takes back — it is re-validated against a fresh
 *                          listing there, never trusted as a path.
 * @param year              the year folder the file sits under, always set
 * @param month             1-12, or null when nothing in the name named a month
 * @param followsConvention false when the period had to fall back to the year folder. Not shown
 *                          to the employee; it is what lets the list sort sensibly and what an
 *                          admin would look at to find files worth renaming.
 * @param sizeBytes         null when Graph did not report it
 * @param lastModified      raw ISO-8601 from Graph
 */
public record MyPayslipDto(String fileName, int year, Integer month, boolean followsConvention,
                           Long sizeBytes, String lastModified) {
}
