package com.daf360.rh.scheduler;

import com.daf360.rh.notification.InterviewNotifier;
import com.daf360.rh.service.PaysTimezoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * « Votre entretien commence dans moins d'une heure » — to every panel member (V132).
 *
 * <p>Every five minutes, so a reminder lands between 55 and 60 minutes before the slot. The
 * slot is stored as the wall clock RH typed, stamped +00:00; its real instant is that wall
 * clock in the candidate's entity zone. Because that zone can be up to ±14 h from UTC, the SQL
 * window is wide and the exact « within the hour » test is done here, per row.
 *
 * <p>Sent once: each row is claimed with a conditional UPDATE on {@code reminder_sent_at}
 * before anything goes out, so two instances, or two overlapping ticks, never both send. A
 * rescheduled interview has the flag cleared by the service and is reminded again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReminderJob {

    private static final Duration LEAD = Duration.ofMinutes(60);

    private static final String DUE_SQL = """
        SELECT ci.id, ci.candidate_id, ci.scheduled_at, ci.location, ci.interviewer_user_id,
               c.pays_id, c.first_name, c.last_name, it.name AS type_name
          FROM [dbo].[candidate_interviews] ci
          JOIN [dbo].[candidates] c ON c.id = ci.candidate_id
          LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id
         WHERE ci.status = 'PLANNED'
           AND ci.reminder_sent_at IS NULL
           AND ci.scheduled_at >= DATEADD(hour, -15, SYSDATETIMEOFFSET())
           AND ci.scheduled_at <= DATEADD(hour,  16, SYSDATETIMEOFFSET())
        """;

    private static final String CLAIM_SQL =
        "UPDATE [dbo].[candidate_interviews] SET reminder_sent_at = SYSDATETIMEOFFSET() " +
        "WHERE id = ? AND reminder_sent_at IS NULL AND status = 'PLANNED'";

    private static final String PANEL_SQL =
        "SELECT user_id FROM [dbo].[candidate_interview_interviewers] WHERE interview_id = ? ORDER BY id";

    private final JdbcTemplate        jdbc;
    private final PaysTimezoneService paysTimezoneService;
    private final InterviewNotifier   notifier;

    private record Due(long id, long candidateId, OffsetDateTime scheduledAt, String location,
                       Long leadId, Long paysId, String candidateName, String typeName) {}

    @Scheduled(cron = "0 */5 * * * *")
    public void sendDueReminders() {
        try {
            Instant now = Instant.now();
            List<Due> rows = jdbc.query(DUE_SQL, (rs, rn) -> {
                String first = rs.getString("first_name");
                String last  = rs.getString("last_name");
                long lead    = rs.getLong("interviewer_user_id");
                Long leadId  = rs.wasNull() ? null : lead;
                long pays    = rs.getLong("pays_id");
                Long paysId  = rs.wasNull() ? null : pays;
                return new Due(rs.getLong("id"), rs.getLong("candidate_id"),
                        rs.getObject("scheduled_at", OffsetDateTime.class), rs.getString("location"),
                        leadId, paysId,
                        ((first != null ? first : "") + " " + (last != null ? last : "")).trim(),
                        rs.getString("type_name"));
            });
            int sent = 0;
            for (Due d : rows) {
                if (d.scheduledAt() == null) continue;
                Instant start = realStart(d.scheduledAt(), d.paysId());
                if (!start.isAfter(now) || start.isAfter(now.plus(LEAD))) continue;
                // Claim first: only the tick that flips the flag sends.
                if (jdbc.update(CLAIM_SQL, d.id()) != 1) continue;
                Set<Long> panel = new LinkedHashSet<>(jdbc.queryForList(PANEL_SQL, Long.class, d.id()));
                if (d.leadId() != null) panel.add(d.leadId());
                if (panel.isEmpty()) continue;
                sent += notifier.reminder(panel, d.candidateId(), d.candidateName(), d.typeName(),
                        d.scheduledAt(), d.location());
            }
            if (sent > 0) log.info("Interview reminders sent: {}", sent);
        } catch (Exception e) {
            log.warn("Interview reminder job failed: {}", e.getMessage());
        }
    }

    private Instant realStart(OffsetDateTime stored, Long paysId) {
        ZoneId zone = paysId != null ? paysTimezoneService.zoneFor(paysId) : null;
        return stored.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                .atZone(zone != null ? zone : ZoneOffset.UTC).toInstant();
    }
}
