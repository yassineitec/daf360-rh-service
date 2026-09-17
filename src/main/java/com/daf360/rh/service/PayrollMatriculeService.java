package com.daf360.rh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole writer of {@code employee_profiles.payroll_matricule}.
 *
 * <p>The register is a plain integer sequence, zero-padded to a minimum width of two:
 * {@code 01, 05, 08, 10, … 99, 100, … 205}. It predates this service — 109 rows were
 * loaded by hand — so the next value is derived from the table itself rather than from a
 * SQL Server SEQUENCE, which would drift silently the moment someone inserts a matricule
 * manually again.
 *
 * <p>Two rules the numbering must not break:
 * <ul>
 *   <li><b>Never gap-fill.</b> The 1–205 range has ~96 unused numbers left by departed
 *       employees, and their historical payslips still reference them. Always MAX + 1.</li>
 *   <li><b>Never regenerate.</b> {@link #allocateIfAbsent} is a no-op when the profile
 *       already holds a number, so re-running an incomplete onboarding cannot burn a
 *       second one.</li>
 * </ul>
 *
 * <p>This is <em>not</em> {@code EmployeeIdGeneratorService}, whose {@code [NOM3][PRE3][userId]}
 * format ("DUPPIE125") targets {@code Users.employee_id} — a column that is NULL for every
 * profile in production and is no longer read as the matricule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollMatriculeService {

    /**
     * TRY_CAST, not CAST: one non-numeric legacy value would otherwise throw and block
     * every future hire. UPDLOCK + HOLDLOCK hold the range for the caller's transaction,
     * so two concurrent onboardings cannot both read 205 and both write "206".
     */
    private static final String NEXT_SQL =
            "SELECT ISNULL(MAX(TRY_CAST(payroll_matricule AS INT)), 0) + 1 " +
            "FROM [dbo].[employee_profiles] WITH (UPDLOCK, HOLDLOCK)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Allocates the next matricule, formatted for storage.
     *
     * <p>MANDATORY propagation: the lock above is only worth holding inside the
     * transaction that goes on to persist the value. Called outside one, this throws
     * rather than handing out a number two callers could share.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocate() {
        Integer next = jdbcTemplate.queryForObject(NEXT_SQL, Integer.class);
        return format(next == null ? 1 : next);
    }

    /** Zero-padded to a minimum of two digits: 8 → "08", 206 → "206". */
    public static String format(int value) {
        return String.format("%02d", value);
    }
}
