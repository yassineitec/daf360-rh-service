package com.daf360.rh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceService {

    private static final String INSERT_SQL =
        "INSERT INTO [dbo].[workflow_instances] " +
        "(event_type, employee_profile_id, triggered_by, pays_id, status, start_date, created_at, updated_at) " +
        "VALUES (?, ?, ?, ?, 'OPEN', ?, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET())";

    private final JdbcTemplate jdbc;

    /**
     * Creates an ONBOARDING workflow instance for the newly created employee profile.
     *
     * @param employeeProfileId the newly saved profile id
     * @param triggeredBy       the HR officer's user id
     * @param paysId            the employee's country id
     * @param startDate         the hire date (workflow start)
     * @return the generated workflow instance id
     */
    @Transactional
    public Long createOnboardingInstance(Long employeeProfileId, Long triggeredBy,
                                          Long paysId, LocalDate startDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SQL,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "ONBOARDING");
            ps.setLong  (2, employeeProfileId);
            ps.setLong  (3, triggeredBy != null ? triggeredBy : 0);
            ps.setLong  (4, paysId);
            ps.setObject(5, startDate);
            return ps;
        }, keyHolder);
        Long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        log.info("Created ONBOARDING workflow instance id={} for profile={}", id, employeeProfileId);
        return id;
    }
}
