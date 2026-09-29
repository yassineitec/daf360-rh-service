package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.PaysTimezoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * What payroll-service needs from RH to run salary advances — which payroll owns (DAF360_PAYROLL
 * V25) — but cannot know itself:
 *
 *  - the employee facts behind the eligibility rules and the monthly export: entity, hire
 *    date, lifecycle status, a departure in progress, the payroll matricule, and the current
 *    payroll month in the entity's own time zone;
 *  - sending the in-app notifications, since the routing engine (rules, recipients, the bell)
 *    lives here.
 *
 * Service-to-service only, authenticated by the {@code X-Internal-Key} shared secret like
 * {@link InternalRegimeController} — fails closed when the key is unset. Paths are opened in
 * SecurityConfig (GET for the facts, POST for the notification).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalSalaryAdvanceController {

    private static final String HEADER = "X-Internal-Key";

    /** A person on leave or on a mission is still paid, so still able to repay. */
    private static final Set<LifecycleStatus> ACTIVE =
            EnumSet.of(LifecycleStatus.ACTIVE, LifecycleStatus.ON_LEAVE, LifecycleStatus.ON_MISSION);

    /** Offboarding statuses that mean the person is leaving — CANCELLED/ARCHIVED do not. */
    private static final String OPEN_OFFBOARDING_SQL =
            "SELECT COUNT(*) FROM [dbo].[offboarding_workflow_instances] "
            + "WHERE employee_profile_id = ? AND status IN ('PENDING','IN_PROGRESS','BLOCKED','VALIDATED')";

    private final EmployeeProfileRepository  profileRepo;
    private final PaysTimezoneService        timezoneService;
    private final NotificationRoutingService notificationRoutingService;
    private final JdbcTemplate               jdbcTemplate;
    private final AppProperties              appProperties;

    /** The facts one employee's advance request is checked against. 204 = no employee profile. */
    @GetMapping("/api/hr/internal/salary-advances/employees/{userId}")
    public ResponseEntity<EmployeeFacts> employee(@PathVariable Long userId,
                                                  @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        return profileRepo.findByUserId(userId)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .map(this::facts)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /** The same facts for a batch — names and matricules of the monthly deduction export. */
    @GetMapping("/api/hr/internal/salary-advances/employees")
    public List<EmployeeFacts> employees(@RequestParam List<Long> userIds,
                                         @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        List<EmployeeFacts> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(userIds)) {
            profileRepo.findByUserId(id)
                    .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                    .map(this::facts)
                    .ifPresent(out::add);
        }
        return out;
    }

    /**
     * Dispatches one salary-advance notification through the routing engine. Fire-and-forget
     * on this side too: the engine is async and never throws, so payroll's own transaction
     * is never at the mercy of a mail server.
     */
    @PostMapping("/api/hr/internal/notifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void notify(@RequestBody NotificationRequest req,
                       @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        if (req.eventCode() == null || !req.eventCode().startsWith("SALARY_ADVANCE_")) {
            // Only the salary-advance events: this endpoint must not become a way for any
            // holder of the key to notify anyone about anything.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported event");
        }
        notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
                .eventCode(req.eventCode())
                .paysId(req.paysId())
                .subjectUserId(req.subjectUserId())
                .templateVars(req.templateVars() == null ? Map.of() : req.templateVars())
                .entityType(NotificationEntityType.SALARY_ADVANCE)
                .entityId(req.entityId())
                .build());
    }

    private EmployeeFacts facts(EmployeeProfile p) {
        ZoneId zone = p.getPaysId() == null ? null : timezoneService.zoneFor(p.getPaysId());
        LocalDate today = zone != null ? LocalDate.now(zone) : LocalDate.now();
        Integer offboarding = jdbcTemplate.queryForObject(OPEN_OFFBOARDING_SQL, Integer.class, p.getId());
        String name = jdbcTemplate.query(
                "SELECT fullName FROM [dbo].[Users] WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, p.getUserId());
        return new EmployeeFacts(
                p.getUserId(),
                p.getId(),
                name,
                p.getPaysId(),
                p.getHireDate(),
                p.getLifecycleStatus() == null ? null : p.getLifecycleStatus().name(),
                p.getLifecycleStatus() != null && ACTIVE.contains(p.getLifecycleStatus()),
                offboarding != null && offboarding > 0,
                p.getPayrollMatricule(),
                YearMonth.from(today).toString());
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        if (provided == null || !java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }

    /** What payroll reads. {@code currentMonth} is "yyyy-MM" in the entity's own zone. */
    public record EmployeeFacts(
            Long      userId,
            Long      profileId,
            String    fullName,
            Long      paysId,
            LocalDate hireDate,
            String    lifecycleStatus,
            boolean   active,
            boolean   offboardingInProgress,
            String    payrollMatricule,
            String    currentMonth
    ) {}

    public record NotificationRequest(
            String              eventCode,
            Long                paysId,
            Long                subjectUserId,
            Long                entityId,
            Map<String, String> templateVars
    ) {}
}
