# Employee Payroll Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each employee their own payroll configuration (country, contract type,
benefits-in-kind, current net salary) editable from a new "Rémunération" tab on the RH
employee profile page and a new screen in the Payroll app, both backed by one new entity in
`daf360-payroll-service`, which becomes the sole source of current salary going forward.

**Architecture:** New per-employee entity + append-only history table in
`daf360-payroll-service` (mirroring `daf360-rh-service`'s Working-Time-Regime
assignment-history pattern), exposed via a new REST resource. Two Angular frontends
(`daf360-payroll-frontend`, `daf360-rh-frontend`) call it directly — `daf360-rh-frontend`
bypasses its own backend exactly as `PayrollSimulationService` already does. `daf360-rh-service`
only needs its `PermissionCatalog` updated (mirroring convention) — no functional change there.

**Tech Stack:** Spring Boot (Flyway/SQL Server on the backend), Angular standalone
components/signals on both frontends.

**Design doc:** `docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md`

**Before you start:** NO-COMMIT session — nothing gets `git add`/`git commit`/`git push`
without explicit user permission. Every task ends with a "confirm scope, DO NOT STAGE OR
COMMIT" step, in every repo touched. Note: `daf360-payroll-service`'s 22 previously-deleted
migration files have ALREADY been restored from git history (verified byte-identical) as a
prerequisite fix, sitting uncommitted alongside this feature's own new files — do not revert
that restoration.

Repos touched, with the exact absolute paths used throughout this plan:
- `c:\Users\ITEC2\OneDrive\Documents\projects\daf360-payroll-service`
- `c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-service`
- `c:\Users\ITEC2\OneDrive\Documents\projects\daf360-payroll-frontend`
- `c:\Users\ITEC2\OneDrive\Documents\projects\daf360-rh-frontend`

No unit tests are added anywhere in `daf360-payroll-service` for this feature — the whole
repo has exactly one test file (`engine/TopologicalEvaluatorTest.java`, testing the formula
engine only); no service/controller in this codebase has test coverage, so this follows the
established convention rather than inventing one. Verification is compile + build + a manual
hand-trace (Task 7).

---

### Task 1: Database — migration, entity, repository (`daf360-payroll-service`)

**Files:**
- Create: `src/main/resources/db/migration/V23__employee_payroll_config.sql`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfig.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/entity/EmployeePayrollConfigHistory.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/repository/EmployeePayrollConfigRepository.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/repository/EmployeePayrollConfigHistoryRepository.java`

- [ ] **Step 1: Write the migration**

```sql
-- V23__employee_payroll_config.sql
--
-- Per-employee payroll configuration (country, contract type, selected benefits-in-kind,
-- current net salary), the first per-EMPLOYEE scoping anywhere in this schema — every
-- existing parameter_sets/payroll_parameter_sets row is scoped to pays_id only. Mirrors
-- daf360-rh-service's Working-Time-Regime pattern: one mutable "current" row plus an
-- append-only history table with a required reason, since this changes real pay.
--
-- current_net_salary starts NULL for every employee (no bulk backfill from RH's existing
-- salaireNetRh — see docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md §6)
-- and is populated one employee at a time, from either app, going forward.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_config')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_config] (
        [id]                     BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]        BIGINT                NOT NULL,
        [pays_id]                BIGINT                NOT NULL,
        [contract_type]          NVARCHAR(255)         NOT NULL,  -- CDI|CDD|STAGE|CIVP
        [selected_benefit_codes] NVARCHAR(MAX)         NULL,      -- JSON array of codes, null = none selected
        [current_net_salary]     NUMERIC(18,3)         NULL,
        [updated_by]             BIGINT                NOT NULL,
        [updated_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [created_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_config]     PRIMARY KEY ([id]),
        CONSTRAINT [UX_epc_profile_user_id]         UNIQUE ([profile_user_id]),
        CONSTRAINT [CK_epc_contract_type]           CHECK ([contract_type] IN ('CDI','CDD','STAGE','CIVP'))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'employee_payroll_config_history')
BEGIN
    CREATE TABLE [dbo].[employee_payroll_config_history] (
        [id]                     BIGINT IDENTITY(1,1)  NOT NULL,
        [profile_user_id]        BIGINT                NOT NULL,
        [pays_id]                BIGINT                NOT NULL,
        [contract_type]          NVARCHAR(255)         NOT NULL,
        [selected_benefit_codes] NVARCHAR(MAX)         NULL,
        [current_net_salary]     NUMERIC(18,3)         NULL,
        [reason]                 NVARCHAR(500)         NOT NULL,
        [changed_by]             BIGINT                NOT NULL,
        [changed_at]             DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_employee_payroll_config_history] PRIMARY KEY ([id])
    );
    CREATE INDEX [IX_epch_profile_user_id] ON [dbo].[employee_payroll_config_history] ([profile_user_id]);
END
GO
```

- [ ] **Step 2: Write the entity**

```java
package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_payroll_config")
@Getter @Setter
public class EmployeePayrollConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false, unique = true)
    private Long profileUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;  // CDI|CDD|STAGE|CIVP

    @Column(name = "selected_benefit_codes", columnDefinition = "NVARCHAR(MAX)")
    private String selectedBenefitCodes;  // JSON array, null = none selected

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 3: Write the history entity**

```java
package com.daf360.payroll.modules.employeeconfig.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "employee_payroll_config_history")
@Getter @Setter
public class EmployeePayrollConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_user_id", nullable = false)
    private Long profileUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;

    @Column(name = "selected_benefit_codes", columnDefinition = "NVARCHAR(MAX)")
    private String selectedBenefitCodes;

    @Column(name = "current_net_salary")
    private BigDecimal currentNetSalary;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
```

- [ ] **Step 4: Write the repositories**

```java
package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeePayrollConfigRepository extends JpaRepository<EmployeePayrollConfig, Long> {
    Optional<EmployeePayrollConfig> findByProfileUserId(Long profileUserId);
}
```

```java
package com.daf360.payroll.modules.employeeconfig.repository;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeePayrollConfigHistoryRepository extends JpaRepository<EmployeePayrollConfigHistory, Long> {
    List<EmployeePayrollConfigHistory> findByProfileUserIdOrderByChangedAtDesc(Long profileUserId);
}
```

- [ ] **Step 5: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/main/resources/db/migration/V23__employee_payroll_config.sql src/main/java/com/daf360/payroll/modules/employeeconfig/
```

Expected: all new files show as `??` (untracked), nothing staged.

---

### Task 2: API — DTOs, permissions, service, controller (`daf360-payroll-service`)

**Files:**
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigDto.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/UpsertEmployeePayrollConfigRequest.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/dto/EmployeePayrollConfigHistoryDto.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/service/EmployeePayrollConfigService.java`
- Create: `src/main/java/com/daf360/payroll/modules/employeeconfig/controller/EmployeePayrollConfigController.java`
- Modify: `src/main/java/com/daf360/payroll/security/PermissionCatalog.java`

- [ ] **Step 1: Add the two new permission codes**

Find this exact current block:

```java
    public static final String IMPORT_CALIBRATION          = "PAYROLL_IMPORT_CALIBRATION";
    /** Country Director role: read-only view of aggregate budget/forecast data, no individual salary access. */
    public static final String VIEW_BUDGET_AGGREGATE       = "PAYROLL_VIEW_BUDGET_AGGREGATE";

    /** All codes — used to validate PermissionCatalog entries in RH service. */
    public static final Set<String> ALL_CODES = Set.of(
            RUN_SIMULATION, VIEW_INDIVIDUAL, APPROVE_PARAMSET, RUN_CALIBRATION,
            EXPORT_BUDGET, IMPORT_PARTNER, VIEW_AGGREGATE, APPROVE_PARAMSET_FAST_TRACK,
            VIEW_PARAMSET, UPLOAD_ACTUAL, SUPER_ADMIN,
            RUN_ENGINE, VIEW_RESULTS, MANAGE_RUBRIQUES, MANAGE_COUNTRIES,
            IMPORT_CALIBRATION, VIEW_BUDGET_AGGREGATE
    );
```

Replace it with:

```java
    public static final String IMPORT_CALIBRATION          = "PAYROLL_IMPORT_CALIBRATION";
    /** Country Director role: read-only view of aggregate budget/forecast data, no individual salary access. */
    public static final String VIEW_BUDGET_AGGREGATE       = "PAYROLL_VIEW_BUDGET_AGGREGATE";
    public static final String VIEW_EMPLOYEE_CONFIG        = "PAYROLL_VIEW_EMPLOYEE_CONFIG";
    public static final String MANAGE_EMPLOYEE_CONFIG      = "PAYROLL_MANAGE_EMPLOYEE_CONFIG";

    /** All codes — used to validate PermissionCatalog entries in RH service. */
    public static final Set<String> ALL_CODES = Set.of(
            RUN_SIMULATION, VIEW_INDIVIDUAL, APPROVE_PARAMSET, RUN_CALIBRATION,
            EXPORT_BUDGET, IMPORT_PARTNER, VIEW_AGGREGATE, APPROVE_PARAMSET_FAST_TRACK,
            VIEW_PARAMSET, UPLOAD_ACTUAL, SUPER_ADMIN,
            RUN_ENGINE, VIEW_RESULTS, MANAGE_RUBRIQUES, MANAGE_COUNTRIES,
            IMPORT_CALIBRATION, VIEW_BUDGET_AGGREGATE,
            VIEW_EMPLOYEE_CONFIG, MANAGE_EMPLOYEE_CONFIG
    );
```

- [ ] **Step 2: Write the DTOs**

```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        Long updatedBy,
        OffsetDateTime updatedAt
) {}
```

```java
package com.daf360.payroll.modules.employeeconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record UpsertEmployeePayrollConfigRequest(
        @NotNull Long paysId,
        @NotNull String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        @NotBlank String reason
) {}
```

```java
package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigHistoryDto(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentNetSalary,
        String reason,
        Long changedBy,
        OffsetDateTime changedAt
) {}
```

- [ ] **Step 3: Write the service**

```java
package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Per-employee payroll configuration — the first per-EMPLOYEE (not per-country) scoping
 * anywhere in this service. See
 * docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md.
 */
@Service
public class EmployeePayrollConfigService {

    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final ObjectMapper objectMapper;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         ObjectMapper objectMapper) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Existing config if one was ever saved, otherwise a not-yet-persisted default built
     * from the employee's own profile (paysId/contractType) via HrEmployeeClient — the same
     * convenience-hydration client IndividualSimulationService already uses, and just as
     * non-fatal if HR is unreachable (falls through to a NoSuchElementException the
     * controller turns into a 404 either way, matching "not configured yet, and no sensible
     * default is available" — the frontend renders that as an empty form).
     */
    public EmployeePayrollConfigDto getOrDefault(Long profileUserId) {
        Optional<EmployeePayrollConfig> existing = repo.findByProfileUserId(profileUserId);
        if (existing.isPresent()) return toDto(existing.get());

        HrEmployeeClient.HrEmployeeDto hr = hrEmployeeClient.findEmployeeByUserId(profileUserId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No payroll config and no HR profile for profileUserId=" + profileUserId));
        return new EmployeePayrollConfigDto(
                profileUserId, hr.paysId(), hr.contractType() != null ? hr.contractType() : "CDI",
                List.of(), null, null, null);
    }

    public EmployeePayrollConfigHistoryDto[] getHistory(Long profileUserId) {
        return historyRepo.findByProfileUserIdOrderByChangedAtDesc(profileUserId).stream()
                .map(this::toHistoryDto)
                .toArray(EmployeePayrollConfigHistoryDto[]::new);
    }

    public EmployeePayrollConfigDto upsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req, Long actingUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        String benefitCodesJson = toJson(req.selectedBenefitCodes());

        EmployeePayrollConfig entity = repo.findByProfileUserId(profileUserId).orElseGet(() -> {
            EmployeePayrollConfig created = new EmployeePayrollConfig();
            created.setProfileUserId(profileUserId);
            created.setCreatedAt(now);
            return created;
        });
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentNetSalary(req.currentNetSalary());
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(req.contractType());
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentNetSalary(req.currentNetSalary());
        history.setReason(req.reason());
        history.setChangedBy(actingUserId);
        history.setChangedAt(now);
        historyRepo.save(history);

        return toDto(entity);
    }

    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentNetSalary(),
                e.getUpdatedBy(), e.getUpdatedAt());
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt());
    }

    private String toJson(List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid benefit codes", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Write the controller**

```java
package com.daf360.payroll.modules.employeeconfig.controller;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.service.EmployeePayrollConfigService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payroll/employee-configs")
public class EmployeePayrollConfigController {

    private final EmployeePayrollConfigService service;
    private final UserContextService userContext;

    public EmployeePayrollConfigController(EmployeePayrollConfigService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping("/{profileUserId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto get(@PathVariable Long profileUserId) {
        return service.getOrDefault(profileUserId);
    }

    @GetMapping("/{profileUserId}/history")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigHistoryDto[] history(@PathVariable Long profileUserId) {
        return service.getHistory(profileUserId);
    }

    @PutMapping("/{profileUserId}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "')")
    public EmployeePayrollConfigDto upsert(@PathVariable Long profileUserId,
                                            @Valid @RequestBody UpsertEmployeePayrollConfigRequest req) {
        return service.upsert(profileUserId, req, userContext.currentUserId());
    }
}
```

- [ ] **Step 5: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/main/java/com/daf360/payroll/modules/employeeconfig/ src/main/java/com/daf360/payroll/security/PermissionCatalog.java
```

---

### Task 3: Backend verification (`daf360-payroll-service`)

Pure verification — single-pass, no reviewer needed.

- [ ] **Step 1: Compile**

```bash
mvn -q -o compile
```

Expected: no output (clean compile). If `HrEmployeeClient.HrEmployeeDto` field access fails,
re-check its exact record component names against
`src/main/java/com/daf360/payroll/modules/simulation/client/HrEmployeeClient.java`.

- [ ] **Step 2: Confirm the migration folder is exactly 23 files, sequential, no gaps**

```bash
ls src/main/resources/db/migration/ | sort -V
```

Expected: `V1__baseline.sql` through `V23__employee_payroll_config.sql`, no gaps, no
duplicated version numbers.

- [ ] **Step 3: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short
git log -3 --oneline
```

Report DONE or DONE_WITH_CONCERNS.

---

### Task 4: Permission mirror (`daf360-rh-service`)

**Files:**
- Modify: `src/main/java/com/daf360/rh/common/PermissionCatalog.java`

- [ ] **Step 1: Mirror the two new codes**

Find this exact current block:

```java
    public static final String PAYROLL_MANAGE_RUBRIQUES            = "PAYROLL_MANAGE_RUBRIQUES";
    public static final String PAYROLL_MANAGE_COUNTRIES            = "PAYROLL_MANAGE_COUNTRIES";
    public static final String PAYROLL_IMPORT_CALIBRATION          = "PAYROLL_IMPORT_CALIBRATION";
```

Replace it with:

```java
    public static final String PAYROLL_MANAGE_RUBRIQUES            = "PAYROLL_MANAGE_RUBRIQUES";
    public static final String PAYROLL_MANAGE_COUNTRIES            = "PAYROLL_MANAGE_COUNTRIES";
    public static final String PAYROLL_IMPORT_CALIBRATION          = "PAYROLL_IMPORT_CALIBRATION";
    public static final String PAYROLL_VIEW_EMPLOYEE_CONFIG        = "PAYROLL_VIEW_EMPLOYEE_CONFIG";
    public static final String PAYROLL_MANAGE_EMPLOYEE_CONFIG      = "PAYROLL_MANAGE_EMPLOYEE_CONFIG";
```

Then find the aggregate set a few lines below (read the file first to get its exact current
name and member list — the earlier investigation found it around line 190 but did not quote
its closing content precisely) and add `PAYROLL_VIEW_EMPLOYEE_CONFIG, PAYROLL_MANAGE_EMPLOYEE_CONFIG`
to it the same way `PAYROLL_MANAGE_COUNTRIES` was already added, so this stays consistent
with every other code in the file — if the current content doesn't match what's quoted here
exactly, report NEEDS_CONTEXT with the real content rather than guessing.

- [ ] **Step 2: Compile**

```bash
mvn -q -o compile
```

Expected: no output.

- [ ] **Step 3: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/main/java/com/daf360/rh/common/PermissionCatalog.java
```

Note for the final report: granting `PAYROLL_VIEW_EMPLOYEE_CONFIG`/`PAYROLL_MANAGE_EMPLOYEE_CONFIG`
to HR_MANAGER/FINANCE_MANAGER (or whichever roles should have them) is a post-deploy admin
action via the existing role-permission admin UI — no code seeds this, matching how every
other `PAYROLL_*` permission was rolled out (§2 of the design doc).

---

### Task 5: Payroll-frontend — employee config screen (`daf360-payroll-frontend`)

**Files:**
- Create: `src/app/modules/employee-config/employee-config.routes.ts`
- Create: `src/app/modules/employee-config/employee-config.component.ts`
- Create: `src/app/modules/employee-config/employee-config.service.ts`
- Modify: `src/app/app.routes.ts`
- Modify: `src/app/core/payroll-nav.ts`
- Modify: `public/i18n/fr.json`
- Modify: `public/i18n/en.json`

Read `src/app/modules/simulator/simulator.routes.ts` and `simulator.component.ts` first for
the exact standalone-route/component conventions (lazy `loadComponent`, `ChangeDetectionStrategy.OnPush`,
signals) before writing these files — mirror them exactly rather than inventing a new shape.

- [ ] **Step 1: Write the service**

```ts
import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface EmployeePayrollConfigDto {
  profileUserId: number;
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class EmployeeConfigService {
  private http = inject(HttpClient);
  private base = `${environment.apiUrl}/api/payroll/employee-configs`;

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }
}
```

Before finalizing this step: read `src/app/modules/simulator/simulator.component.ts`'s own
HTTP-calling service (or wherever it builds its base URL) to confirm the exact environment key
this app's own backend calls use (likely `environment.apiUrl` or similar, since this frontend
IS the payroll app talking to its own backend, unlike rh-frontend's cross-service call) — if
the real key differs from `environment.apiUrl` as guessed here, use the real one instead.

- [ ] **Step 2: Write the component (search + config form)**

```ts
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { EmployeeSelectComponent } from '../../shared/employee-select/employee-select.component';
import { EmployeeConfigService, EmployeePayrollConfigDto } from './employee-config.service';

@Component({
  selector: 'app-employee-config',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, EmployeeSelectComponent, TranslatePipe],
  templateUrl: './employee-config.component.html',
})
export class EmployeeConfigComponent {
  private svc = inject(EmployeeConfigService);
  private translate = inject(TranslateService);

  readonly selectedUserId = signal<number | null>(null);
  readonly config = signal<EmployeePayrollConfigDto | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly reason = signal('');
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);

  onEmployeeSelected(userId: number | null): void {
    this.selectedUserId.set(userId);
    this.config.set(null);
    this.error.set(null);
    this.success.set(null);
    if (userId === null) return;
    this.loading.set(true);
    this.svc.get(userId).subscribe({
      next: cfg => { this.config.set(cfg); this.loading.set(false); },
      error: () => { this.loading.set(false); this.error.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.LOAD_ERROR')); },
    });
  }

  save(): void {
    const userId = this.selectedUserId();
    const cfg = this.config();
    if (userId === null || cfg === null || this.reason().trim() === '') return;
    this.saving.set(true);
    this.error.set(null);
    this.svc.upsert(userId, {
      paysId: cfg.paysId,
      contractType: cfg.contractType,
      selectedBenefitCodes: cfg.selectedBenefitCodes,
      currentNetSalary: cfg.currentNetSalary,
      reason: this.reason(),
    }).subscribe({
      next: updated => {
        this.saving.set(false);
        this.config.set(updated);
        this.reason.set('');
        this.success.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.SAVE_SUCCESS'));
      },
      error: () => { this.saving.set(false); this.error.set(this.translate.instant('PAYROLL.EMPLOYEE_CONFIG.SAVE_ERROR')); },
    });
  }
}
```

Then write `employee-config.component.html`: an `<app-employee-select>` bound to
`selectedUserId` (mirroring `simulator.component.html`'s own usage at line ~203), and once
`config()` is non-null, a form with: `paysId` (read from the loaded config; a select if this
app already has a pays/country picker component reused elsewhere — check `simulator`'s step 1
for one before inventing a new one), `contractType` (select: CDI/CDD/STAGE/CIVP),
`selectedBenefitCodes` (checkboxes/multi-select — check if a benefits-picker component
already exists anywhere in this app, e.g. under `admin` or `simulator`, before building a new
one), `currentNetSalary` (number input), a required `reason` text input, and a save button
(`loading: saving()`, `disabled: saving() || reason().trim() === ''`), plus inline
success/error text mirroring the pattern already used elsewhere in this codebase (or in
rh-frontend's `candidate-salary-section.component.ts`, the closest analogous precedent).

- [ ] **Step 3: Write the routes file**

```ts
import { Routes } from '@angular/router';

export const EMPLOYEE_CONFIG_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./employee-config.component').then(m => m.EmployeeConfigComponent) },
];
```

- [ ] **Step 4: Wire the route into `app.routes.ts`**

Find this exact current block:

```ts
      {
        path: 'payslips',
        canActivate: [permissionGuard],
        data: { permissions: PAYROLL_PAYSLIPS_PERMISSIONS },
        loadChildren: () =>
          import('./modules/payslips/payslips.routes').then(m => m.PAYSLIPS_ROUTES),
      },

      // ── Disabled for now — keep in sync with core/payroll-nav.ts ──────────────
```

Replace it with:

```ts
      {
        path: 'payslips',
        canActivate: [permissionGuard],
        data: { permissions: PAYROLL_PAYSLIPS_PERMISSIONS },
        loadChildren: () =>
          import('./modules/payslips/payslips.routes').then(m => m.PAYSLIPS_ROUTES),
      },
      {
        path: 'employee-config',
        canActivate: [permissionGuard],
        data: { permissions: PAYROLL_EMPLOYEE_CONFIG_PERMISSIONS },
        loadChildren: () =>
          import('./modules/employee-config/employee-config.routes').then(m => m.EMPLOYEE_CONFIG_ROUTES),
      },

      // ── Disabled for now — keep in sync with core/payroll-nav.ts ──────────────
```

And add the import at the top, next to the existing `PAYROLL_*_PERMISSIONS` import:

Find:
```ts
import {
  PAYROLL_ADMIN_PERMISSIONS,
  PAYROLL_PAYSLIPS_PERMISSIONS,
  PAYROLL_SIMULATOR_PERMISSIONS,
} from './core/payroll-nav';
```

Replace with:
```ts
import {
  PAYROLL_ADMIN_PERMISSIONS,
  PAYROLL_EMPLOYEE_CONFIG_PERMISSIONS,
  PAYROLL_PAYSLIPS_PERMISSIONS,
  PAYROLL_SIMULATOR_PERMISSIONS,
} from './core/payroll-nav';
```

- [ ] **Step 5: Add the nav entry and permission constant in `core/payroll-nav.ts`**

Find this exact current block:

```ts
export const PAYROLL_PAYSLIPS_PERMISSIONS = ['RH_MANAGE_PAYSLIPS'];
```

Replace it with:

```ts
export const PAYROLL_PAYSLIPS_PERMISSIONS = ['RH_MANAGE_PAYSLIPS'];

/** `/payroll/employee-config` — assigning a payroll configuration (country, contract type,
 *  benefits, current net salary) to a specific employee. Viewing needs either code;
 *  the backend's own PUT enforces MANAGE specifically. */
export const PAYROLL_EMPLOYEE_CONFIG_PERMISSIONS = [
  'PAYROLL_VIEW_EMPLOYEE_CONFIG',
  'PAYROLL_MANAGE_EMPLOYEE_CONFIG',
];
```

Then find:

```ts
  {
    id:          'payslips',
    label:       'Fiches de paie',
    icon:        'receipt_long',
    route:       'payslips',
    permissions: PAYROLL_PAYSLIPS_PERMISSIONS,
  },

  // ── Disabled for now — keep in sync with app.routes.ts ──────────────────────
```

Replace it with:

```ts
  {
    id:          'payslips',
    label:       'Fiches de paie',
    icon:        'receipt_long',
    route:       'payslips',
    permissions: PAYROLL_PAYSLIPS_PERMISSIONS,
  },
  {
    id:          'employee-config',
    label:       'Configuration employé',
    icon:        'manage_accounts',
    route:       'employee-config',
    permissions: PAYROLL_EMPLOYEE_CONFIG_PERMISSIONS,
  },

  // ── Disabled for now — keep in sync with app.routes.ts ──────────────────────
```

- [ ] **Step 6: i18n — add `PAYROLL.EMPLOYEE_CONFIG.*` keys to `public/i18n/fr.json` and `en.json`**

Add a new top-level block (placement: alongside the existing `PAYROLL.SIMULATOR`/`PAYROLL.ADMIN`
blocks — read the file first to match the exact nesting level and comma placement):

fr.json:
```json
"EMPLOYEE_CONFIG": {
  "TITLE": "Configuration employé",
  "SELECT_EMPLOYEE": "Sélectionner un employé",
  "COUNTRY": "Pays",
  "CONTRACT_TYPE": "Type de contrat",
  "BENEFITS": "Avantages en nature",
  "CURRENT_SALARY": "Salaire net actuel",
  "REASON": "Motif du changement",
  "SAVE": "Enregistrer",
  "SAVE_SUCCESS": "Configuration enregistrée.",
  "SAVE_ERROR": "Erreur lors de l'enregistrement.",
  "LOAD_ERROR": "Erreur lors du chargement."
}
```

en.json:
```json
"EMPLOYEE_CONFIG": {
  "TITLE": "Employee Configuration",
  "SELECT_EMPLOYEE": "Select an employee",
  "COUNTRY": "Country",
  "CONTRACT_TYPE": "Contract Type",
  "BENEFITS": "Benefits in Kind",
  "CURRENT_SALARY": "Current Net Salary",
  "REASON": "Reason for Change",
  "SAVE": "Save",
  "SAVE_SUCCESS": "Configuration saved.",
  "SAVE_ERROR": "Error saving configuration.",
  "LOAD_ERROR": "Error loading configuration."
}
```

- [ ] **Step 7: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/app/modules/employee-config/ src/app/app.routes.ts src/app/core/payroll-nav.ts public/i18n/fr.json public/i18n/en.json
```

---

### Task 6: Payroll-frontend verification (`daf360-payroll-frontend`)

- [ ] **Step 1: JSON validity**

```bash
node -e "JSON.parse(require('fs').readFileSync('public/i18n/fr.json','utf8')); JSON.parse(require('fs').readFileSync('public/i18n/en.json','utf8')); console.log('both valid JSON')"
```

- [ ] **Step 2: TypeScript compile + build**

```bash
npx tsc --noEmit
npm run build
```

Expected: clean compile, build succeeds.

- [ ] **Step 3: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short
```

Report DONE or DONE_WITH_CONCERNS.

---

### Task 7: RH-frontend — new "Rémunération" tab (`daf360-rh-frontend`)

**Files:**
- Create: `src/app/modules/profiles/services/remuneration.service.ts`
- Create: `src/app/modules/profiles/detail-sections/remuneration-section.component.ts`
- Modify: `src/app/modules/profiles/profile-detail.component.ts`
- Modify: `src/app/modules/profiles/profile-detail.component.html`
- Modify: `public/assets/i18n/fr.json`
- Modify: `public/assets/i18n/en.json`

Read `src/app/modules/candidates/payroll-simulation.service.ts` and
`src/app/modules/candidates/detail-sections/candidate-salary-section.component.ts` in full
first — both are the direct precedents for, respectively, calling payroll-service directly
from this app and the self-contained-tab-with-its-own-save-button shape to mirror.

- [ ] **Step 1: Write the service** (mirrors `PayrollSimulationService`'s own direct-call
pattern exactly — same `environment.payrollApiUrl` base, same plain `HttpClient` usage)

```ts
import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface EmployeePayrollConfigDto {
  profileUserId: number;
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export interface UpsertEmployeePayrollConfigRequest {
  paysId: number;
  contractType: string;
  selectedBenefitCodes: string[];
  currentNetSalary: number | null;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class RemunerationService {
  private http = inject(HttpClient);
  private base = `${environment.payrollApiUrl}/api/payroll/employee-configs`;

  get(profileUserId: number): Observable<EmployeePayrollConfigDto> {
    return this.http.get<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`);
  }

  upsert(profileUserId: number, req: UpsertEmployeePayrollConfigRequest): Observable<EmployeePayrollConfigDto> {
    return this.http.put<EmployeePayrollConfigDto>(`${this.base}/${profileUserId}`, req);
  }
}
```

Verify the relative import path to `environments/environment` matches this file's real
location (`src/app/modules/profiles/services/remuneration.service.ts` is one level deeper
than `payroll-simulation.service.ts`'s `src/app/modules/candidates/`, so the `../` count
differs — count directories from the real file path rather than copying the import literally).

- [ ] **Step 2: Write the section component**, following
`candidate-salary-section.component.ts`'s exact shape: `rh-section-card` wrapper, its own
local `loading`/`saving`/`error`/`success` signals, loads on init via `RemunerationService`,
a form (country select, contract type select, benefits checkboxes, current net salary number
field, a required reason field) and its own save button emitting nothing to the parent (unlike
`emploi`/`contact`/`bancaire`'s `patch.emit()` pattern — this tab owns its full read/write
cycle itself, since its data belongs to a different backend than `ProfileUpdateDto`). Only
`profile` is needed as an input (to read the employee's own `userId`/`id` to call the service
with); no `editForm`/`editMode` inputs, since this tab has no relationship to the page's
global edit mode.

- [ ] **Step 3: Add the tab to `profile-detail.component.ts`**

Find:

```ts
type TabId =
  | 'emploi' | 'contact' | 'bancaire'
  // One 'Contrats' tab, not two. 'contrats' (lifecycle) and 'historique' (the manual
  // historique_contrat log) were separate tabs showing two unrelated notions of "contract";
  // they are merged under 'historique' so the lifecycle actions — New contract, which is the
  // only way to change an employee's frozen préavis, plus trial validation / CDD renewal /
  // CDI conversion — stay reachable from one place.
  | 'historique'
  // The IT equipment ledger (it_asset_assignments, V76) — what the employee holds and
  // what they held before. Not part of 'documents': it is inventory, not paperwork.
  | 'materiel' | 'documents';
```

Replace it with:

```ts
type TabId =
  | 'emploi' | 'contact' | 'bancaire'
  // One 'Contrats' tab, not two. 'contrats' (lifecycle) and 'historique' (the manual
  // historique_contrat log) were separate tabs showing two unrelated notions of "contract";
  // they are merged under 'historique' so the lifecycle actions — New contract, which is the
  // only way to change an employee's frozen préavis, plus trial validation / CDD renewal /
  // CDI conversion — stay reachable from one place.
  | 'historique'
  // The IT equipment ledger (it_asset_assignments, V76) — what the employee holds and
  // what they held before. Not part of 'documents': it is inventory, not paperwork.
  | 'materiel' | 'documents'
  // Payroll-backed configuration (country, contract type, benefits, current net salary) —
  // lives in daf360-payroll-service, not this service's own ProfileUpdateDto, so it is
  // self-contained like 'historique'/'materiel', not part of TAB_FIELDS/editForm below. See
  // docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md.
  | 'remuneration';
```

Read the `tabs` computed (lines ~216-242 as of this plan's writing — re-locate it fresh, since
line numbers drift) and add a `remuneration` entry to the returned array, following the exact
shape of the `materiel`/`documents` entries immediately preceding it (label from
`PROFILES.SECTIONS.REMUNERATION`, no count needed unless there's an obvious one — none is
required here). Register `RemunerationSectionComponent` in the `@Component`'s `imports` array,
alongside `ItAssetsSectionComponent` etc.

- [ ] **Step 4: Add the tab's template case in `profile-detail.component.html`**

Find the existing `@case ('materiel') { ... }` or `@case ('documents') { ... }` block (whichever
sits last in the `@switch (activeTab())`) and add a new case immediately after it:

```html
@case ('remuneration') {
  <rh-remuneration-section [profile]="profile()!" />
}
```

(Using the section component's real selector as written in Step 2 — confirm it matches before
finalizing this template edit.)

- [ ] **Step 5: i18n — add the tab label**

Find in `public/assets/i18n/fr.json` (and mirror in `en.json`):

```json
    "SECTIONS": {
      "IDENTITY": "Identité",
      "EMPLOYMENT": "Emploi",
```

Replace the opening of that block with (fr.json):

```json
    "SECTIONS": {
      "IDENTITY": "Identité",
      "REMUNERATION": "Rémunération",
      "EMPLOYMENT": "Emploi",
```

And the equivalent in `en.json` (read its own current `SECTIONS.IDENTITY`/`EMPLOYMENT` values
first — they weren't quoted during investigation, so confirm exact current text before editing,
rather than assuming a direct translation of the French values above).

Also add whatever field-level keys the section component from Step 2 ends up needing (e.g.
`PROFILES.FIELDS.REMUNERATION_COUNTRY`, `..._CONTRACT_TYPE`, `..._BENEFITS`,
`..._CURRENT_SALARY`, `..._REASON`) to both files, following the existing
`PROFILES.FIELDS.*` naming convention seen around `SALARY_CANDIDATE`/`SALARY_RH`.

- [ ] **Step 6: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/app/modules/profiles/ public/assets/i18n/fr.json public/assets/i18n/en.json
```

---

### Task 8: RH-frontend — retire `salaireNetRh`'s edit form (`daf360-rh-frontend`)

**Files:**
- Modify: `src/app/modules/profiles/detail-sections/employment-section.component.ts`
- Modify: `src/app/modules/profiles/profile-detail.component.ts`
- Modify: `public/assets/i18n/fr.json`
- Modify: `public/assets/i18n/en.json`

- [ ] **Step 1: Remove the editable `salaireNetRh` input, replace with a read-only pointer**

Find this exact current block:

```ts
        } @else {
          <div class="flex flex-col gap-4">
            <daf-form-field
              [options]="{ label: ('PROFILES.FIELDS.SALARY_CANDIDATE_INPUT' | translate), type: 'number',
                           placeholder: ('PROFILES.PLACEHOLDERS.SALARY_CANDIDATE' | translate) }"
              [value]="editForm().salaireNetCandidat ?? null"
              (valueChange)="patch.emit({ salaireNetCandidat: asNumber($event) })" />
            <daf-form-field
              [options]="{ label: ('PROFILES.FIELDS.SALARY_RH_INPUT' | translate), type: 'number',
                           placeholder: ('PROFILES.PLACEHOLDERS.SALARY_RH' | translate) }"
              [value]="editForm().salaireNetRh ?? null"
              (valueChange)="patch.emit({ salaireNetRh: asNumber($event) })" />
            <rh-profile-field variant="row"
              [label]="'PROFILES.FIELDS.CURRENCY' | translate" [value]="CURRENCY" />
            <rh-profile-field variant="row" [last]="true"
              [label]="'PROFILES.FIELDS.PAY_FREQUENCY' | translate"
              [value]="'PROFILES.PAY_FREQUENCY.MONTHLY' | translate" />
          </div>
        }
```

Replace it with:

```ts
        } @else {
          <div class="flex flex-col gap-4">
            <daf-form-field
              [options]="{ label: ('PROFILES.FIELDS.SALARY_CANDIDATE_INPUT' | translate), type: 'number',
                           placeholder: ('PROFILES.PLACEHOLDERS.SALARY_CANDIDATE' | translate) }"
              [value]="editForm().salaireNetCandidat ?? null"
              (valueChange)="patch.emit({ salaireNetCandidat: asNumber($event) })" />
            <!-- salaireNetRh is no longer editable here — it's superseded by the payroll-backed
                 current salary on the Rémunération tab (see
                 docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md §5). The
                 historical value stays visible, read-only, so nothing looks like it vanished. -->
            <rh-profile-field variant="row"
              [label]="'PROFILES.FIELDS.SALARY_RH' | translate" [value]="amount(profile().salaireNetRh)" />
            <p class="text-[11px] text-on-surface-variant -mt-2">
              {{ 'PROFILES.FIELDS.SALARY_RH_MOVED_HINT' | translate }}
            </p>
            <rh-profile-field variant="row"
              [label]="'PROFILES.FIELDS.CURRENCY' | translate" [value]="CURRENCY" />
            <rh-profile-field variant="row" [last]="true"
              [label]="'PROFILES.FIELDS.PAY_FREQUENCY' | translate"
              [value]="'PROFILES.PAY_FREQUENCY.MONTHLY' | translate" />
          </div>
        }
```

(This reuses the `amount()` helper the view-mode branch already calls a few lines above —
confirm it's already in scope as a component method before finalizing; it should be, since
the view-mode branch immediately above uses the identical call.)

- [ ] **Step 2: Remove `salaireNetRh` from `TAB_FIELDS.emploi`** (in `profile-detail.component.ts`)

Find:

```ts
  emploi:   ['hireDate', 'contractType', 'contractEndDate', 'probationEndDate', 'isOnProbation',
             'salaireNetCandidat', 'salaireNetRh', 'departmentId', 'gradeId', 'disciplineId', 'nogLevelId'],
```

Replace with:

```ts
  emploi:   ['hireDate', 'contractType', 'contractEndDate', 'probationEndDate', 'isOnProbation',
             'salaireNetCandidat', 'departmentId', 'gradeId', 'disciplineId', 'nogLevelId'],
```

- [ ] **Step 3: Add the new i18n key**

Add to both `public/assets/i18n/fr.json` and `en.json`, in the `PROFILES.FIELDS` block, next
to `SALARY_RH`:

fr.json: `"SALARY_RH_MOVED_HINT": "Géré désormais dans l'onglet Rémunération."`
en.json: `"SALARY_RH_MOVED_HINT": "Now managed in the Rémunération tab."`

(Read each file's real current `PROFILES.FIELDS.SALARY_RH` line first to place this correctly
and match exact comma placement.)

- [ ] **Step 4: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short -- src/app/modules/profiles/detail-sections/employment-section.component.ts src/app/modules/profiles/profile-detail.component.ts public/assets/i18n/fr.json public/assets/i18n/en.json
```

---

### Task 9: RH-frontend verification (`daf360-rh-frontend`)

- [ ] **Step 1: JSON validity**

```bash
node -e "JSON.parse(require('fs').readFileSync('public/assets/i18n/fr.json','utf8')); JSON.parse(require('fs').readFileSync('public/assets/i18n/en.json','utf8')); console.log('both valid JSON')"
```

- [ ] **Step 2: TypeScript compile + build**

```bash
npx tsc --noEmit
npm run build
```

- [ ] **Step 3: Confirm scope, DO NOT STAGE OR COMMIT**

```bash
git status --short
```

Report DONE or DONE_WITH_CONCERNS.

---

### Task 10: Final cross-repo verification and report

Pure verification, no code changes.

- [ ] **Step 1: Hand-trace the end-to-end flow**

Using the real code (not running the app — this session has repeatedly hit an SSO wall on
live browser checks; note that plainly rather than blocking on it):

1. RH-frontend's new Rémunération tab, on load, calls `RemunerationService.get(profileUserId)`
   → hits `daf360-payroll-service`'s `GET /api/payroll/employee-configs/{id}` → if no config
   row exists yet, `EmployeePayrollConfigService.getOrDefault()` calls `HrEmployeeClient
   .findEmployeeByUserId()` → returns a default `paysId`/`contractType` from the employee's
   real RH profile, `currentNetSalary` null. Confirm this chain by reading the actual code at
   each hop — is `profileUserId` really the same identifier space as `HrEmployeeClient`'s
   `userId` parameter (both must refer to the same person via the same ID scheme)?
2. Saving from either app calls `PUT .../employee-configs/{id}` → `upsert()` → confirm it
   both updates the single `employee_payroll_config` row AND appends exactly one
   `employee_payroll_config_history` row, every time, including the very first save.
3. `daf360-rh-frontend`'s `employment-section.component.ts` in edit mode: confirm `salaireNetRh`
   can no longer be changed via `patch.emit()` anywhere in that file, while
   `salaireNetCandidat` still can.
4. Confirm `TAB_FIELDS.emploi` no longer lists `salaireNetRh`, so the per-tab dirty marker
   doesn't falsely flag a change nothing can produce anymore.

Report each as confirmed-by-reading-the-code or flag a mismatch.

- [ ] **Step 2: Confirm nothing is staged or committed anywhere**

```bash
cd daf360-payroll-service && git status --short && git log -3 --oneline
cd ../daf360-rh-service && git status --short && git log -3 --oneline
cd ../daf360-payroll-frontend && git status --short && git log -3 --oneline
cd ../daf360-rh-frontend && git status --short && git log -3 --oneline
```

Expected: only plain `M`/`??` entries everywhere (including the already-restored 22 migration
files in `daf360-payroll-service`, sitting alongside this feature's own new files), nothing
staged, no new commits, in all four repos.

- [ ] **Step 3: Report**

Summarize for the user: what changed, per repo; the full file list; that the 22 deleted
migration files were restored as a prerequisite; that granting the two new permission codes
to roles is a manual post-deploy admin step (not automated); that no bulk backfill of existing
`salaireNetRh` values into the new field was performed (by design, see spec §6); build/compile
results for all four repos; and an explicit statement that everything remains uncommitted in
all four repos, awaiting the user's own commit/push decision.
