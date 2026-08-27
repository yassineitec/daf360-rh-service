package com.daf360.rh.service.payslip;

import com.daf360.rh.service.sharepoint.SharePointPaths;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the period out of a payslip file name, and checks the file names the employee whose
 * folder it sits in.
 *
 * <p>The agreed convention is {@code FirstName_LASTNAME_Month_Year.PDF} under
 * {@code .../01_Pay-Slip/{year}/}. That makes the period deterministic rather than guessed,
 * which is why this class exists as pure arithmetic with no Graph and no DB: getting a month
 * wrong mislabels somebody's salary, and it is cheap to pin in tests.
 *
 * <p>The convention is however aspirational — the payroll folders do not exist yet — and once
 * they do, HR will be filing by hand. So the parse degrades in steps rather than failing:
 * convention first, then any recognisable month/year token in the name, then the year folder
 * the file was found in. The raw file name is always kept as the display title by the caller,
 * so a total parse failure still shows the employee a row they can recognise.
 */
public final class PaySlipFileName {

    /** Month tokens we accept, folded and lowercased. French and English, long and short —
     *  the convention does not say which, and a wrong guess here is invisible until someone
     *  notices "Mars" filed as March of the wrong year. */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1),  Map.entry("january", 1),  Map.entry("jan", 1),
            Map.entry("fevrier", 2),  Map.entry("february", 2), Map.entry("feb", 2),
            Map.entry("fev", 2),
            Map.entry("mars", 3),     Map.entry("march", 3),    Map.entry("mar", 3),
            Map.entry("avril", 4),    Map.entry("april", 4),    Map.entry("avr", 4),
            Map.entry("apr", 4),
            Map.entry("mai", 5),      Map.entry("may", 5),
            Map.entry("juin", 6),     Map.entry("june", 6),     Map.entry("jun", 6),
            Map.entry("juillet", 7),  Map.entry("july", 7),     Map.entry("juil", 7),
            Map.entry("jul", 7),
            Map.entry("aout", 8),     Map.entry("august", 8),   Map.entry("aug", 8),
            Map.entry("septembre", 9),  Map.entry("september", 9),  Map.entry("sep", 9),
            Map.entry("sept", 9),
            Map.entry("octobre", 10),   Map.entry("october", 10),   Map.entry("oct", 10),
            Map.entry("novembre", 11),  Map.entry("november", 11),  Map.entry("nov", 11),
            Map.entry("decembre", 12),  Map.entry("december", 12),  Map.entry("dec", 12));

    private PaySlipFileName() {}

    /**
     * The period a payslip covers.
     *
     * @param month             1-12, or null when nothing in the name named a month
     * @param year              4-digit year, from the name or falling back to the year folder
     * @param followsConvention true when BOTH month and year came out of the file name
     *                          itself. The admin panel can surface the false cases as files
     *                          worth renaming, instead of them silently reading as "January".
     */
    public record Period(Integer month, Integer year, boolean followsConvention) {}

    /**
     * @param fileName       the SharePoint file name, extension included
     * @param yearFromFolder the {@code {year}} folder the file was listed under, or null
     */
    public static Period parse(String fileName, Integer yearFromFolder) {
        String stem = stripExtension(fileName);
        if (stem.isEmpty()) return new Period(null, yearFromFolder, false);

        Integer month = null;
        Integer year  = null;
        // Tokens, not substrings: a substring search would find "mar" inside a surname and
        // "12" inside a national id. Splitting on the convention's own separators keeps the
        // match anchored to something that was meant to be a field.
        for (String raw : SharePointPaths.fold(stem).split("[_\\-. ]+")) {
            String token = raw.toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;

            if (token.matches("(19|20)\\d{2}")) {
                if (year == null) year = Integer.valueOf(token);
                continue;
            }
            Integer named = MONTHS.get(token);
            if (named != null) {
                if (month == null) month = named;
                continue;
            }
            if (token.matches("\\d{1,2}")) {
                int n = Integer.parseInt(token);
                if (n >= 1 && n <= 12 && month == null) month = n;
            }
        }

        boolean fromNameAlone = month != null && year != null;
        if (year == null) year = yearFromFolder;
        return new Period(month, year, fromNameAlone);
    }

    /**
     * Whether the file name starts with the employee's own name.
     *
     * <p>A cheap integrity check, not a security control: the folder is always recomputed
     * from the caller's JWT, so a file can only be served out of the caller's own folder
     * regardless of what this returns. Its value is catching a misfiled document — HR putting
     * one employee's payslip into another's folder — which no path validation can see, and
     * which is exactly the mistake that would be worst to serve silently.
     *
     * <p>Underscores are treated as spaces so both {@code "Ali_YASSINE BEL HAJ HOUMA_..."}
     * and {@code "Ali_YASSINE_BEL_HAJ_HOUMA_..."} match a multi-word surname.
     */
    public static boolean namesEmployee(String fileName, String employeeFullName) {
        if (employeeFullName == null || employeeFullName.isBlank()) return false;
        String stem = SharePointPaths.fold(stripExtension(fileName).replace('_', ' '))
                .toLowerCase(Locale.ROOT);
        String name = SharePointPaths.fold(employeeFullName).toLowerCase(Locale.ROOT);
        return !name.isEmpty() && stem.startsWith(name);
    }

    /** Extension dropped case-insensitively — the convention writes {@code .PDF}. */
    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        String trimmed = fileName.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    /** Convenience for callers that only want a sortable key. */
    public static Optional<Integer> sortKey(Period p) {
        if (p == null || p.year() == null) return Optional.empty();
        return Optional.of(p.year() * 100 + (p.month() == null ? 0 : p.month()));
    }
}
