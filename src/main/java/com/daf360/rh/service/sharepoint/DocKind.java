package com.daf360.rh.service.sharepoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kinds of document the SharePoint integration knows how to locate, and the rules a
 * configured {@code path_template} must satisfy for each.
 *
 * <p>This enum is the vocabulary behind {@code sharepoint_locations.doc_kind}. Rows carrying
 * a value absent from it are ignored on read rather than treated as an error, so a path can
 * be prepared in the database before the code that consumes it ships.
 *
 * <p>Deliberately NOT a per-kind folder-naming strategy. Every kind resolves the employee
 * folder the same way — the convention is "Firstname LASTNAME", which is exactly the format
 * {@code Users.fullName} already holds, in both the contracts tree and the payroll tree.
 * Folders that deviate (one real example: {@code "Bilel ZEDINI-CDI-ARX tunisie"} under
 * 03_Payroll-Admin) are data to correct, either by renaming the folder or by recording a
 * MANUAL override in {@code employee_sharepoint_folders}. They are explicitly NOT a reason to
 * loosen matching: prefix or fuzzy matching on a payslip folder can only fail by showing one
 * employee another's salary.
 */
public enum DocKind {

    /** Profile photo — the employee folder's identity subfolder. Not year-scoped. */
    PHOTO(false),

    /** Payslips, filed one folder per year under 01_Pay-Slip. */
    PAYSLIP(true),

    /** Salary certificates, flat under 02_Salary-Certificate. */
    SALARY_CERTIFICATE(false);

    /** Any token of the form {@code {...}}, so an unknown one is reported rather than
     *  silently surviving into a path and 404ing at request time. */
    private static final Pattern ANY_TOKEN = Pattern.compile("\\{[^}]*}");

    private final boolean yearScoped;

    DocKind(boolean yearScoped) {
        this.yearScoped = yearScoped;
    }

    /** Whether this kind's files sit under one folder per year, i.e. whether its template
     *  must carry {@link SharePointPaths#YEAR_TOKEN}. */
    public boolean isYearScoped() {
        return yearScoped;
    }

    /**
     * Parses a stored or submitted kind, case- and whitespace-insensitively.
     *
     * @return empty for null, blank, or any value this build does not know — never throws,
     *         so an unrecognised database row degrades to "no configuration".
     */
    public static Optional<DocKind> from(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /**
     * Checks a path template against this kind's rules.
     *
     * <p>Returns message KEYS, not sentences: the admin form that will call this (phase C)
     * renders them through the existing i18n files, and the same keys are usable in a log
     * line. An empty list means the template is valid.
     *
     * <p>Validating here rather than in the form is what keeps a hand-written SQL insert and
     * an admin edit held to the same standard — the resolver calls this too, and treats a
     * failing template as NO_CONFIG rather than building a path it knows is wrong.
     */
    public List<String> validateTemplate(String template) {
        List<String> problems = new ArrayList<>();
        if (template == null || template.isBlank()) {
            problems.add("SHAREPOINT.TEMPLATE.BLANK");
            return problems; // nothing further is meaningful
        }
        String t = template.trim();

        if (!t.contains(SharePointPaths.EMPLOYEE_FOLDER_TOKEN)) {
            problems.add("SHAREPOINT.TEMPLATE.MISSING_EMPLOYEE_FOLDER");
        }
        boolean hasYear = t.contains(SharePointPaths.YEAR_TOKEN);
        if (yearScoped && !hasYear) {
            problems.add("SHAREPOINT.TEMPLATE.YEAR_REQUIRED");
        }
        if (!yearScoped && hasYear) {
            problems.add("SHAREPOINT.TEMPLATE.YEAR_NOT_ALLOWED");
        }
        // A path that starts or ends with a separator, or walks upwards, would either build
        // an empty segment or escape the configured tree entirely.
        if (t.startsWith("/") || t.endsWith("/") || t.contains("//") || t.contains("..")) {
            problems.add("SHAREPOINT.TEMPLATE.INVALID_PATH");
        }

        Matcher m = ANY_TOKEN.matcher(t);
        while (m.find()) {
            String token = m.group();
            if (!SharePointPaths.EMPLOYEE_FOLDER_TOKEN.equals(token)
                    && !SharePointPaths.YEAR_TOKEN.equals(token)) {
                problems.add("SHAREPOINT.TEMPLATE.UNKNOWN_TOKEN");
                break; // one report is enough; the form highlights the whole field
            }
        }
        return problems;
    }
}
