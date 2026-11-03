# Generic Audit Trail — Phase 3: RH Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roll the generic audit-trail mechanism out to every table in DAF360_HR, wire actor capture into `daf360-rh-service`, and give RH's existing-but-orphaned `AuditLogComponent` a real home that shows both the old `audit_log` and the new `audit_trail` data together.

**Architecture:** Generate `audit_trail` + per-table triggers in `daf360-sql/db-rh/migration/` using the Phase 0 tool, wrap RH's `DataSource` so every connection carries `SESSION_CONTEXT('app_user')`, merge `audit_log` and `audit_trail` into one timeline via a native SQL `UNION ALL` query (same pattern correctness concern as any cross-table merge: true SQL-level pagination via `OFFSET`/`FETCH`, not an in-memory merge-then-paginate, which would be wrong for page > 0), and extend the existing orphaned Angular screen to render it.

**Tech Stack:** SQL Server (T-SQL triggers, native `UNION ALL` query), Spring Boot 3 / Spring Data JPA (native query with interface projection + `Pageable`), Angular standalone components.

**Depends on:** Phase 0 (`daf360-sql/docs/superpowers/plans/2026-11-03-audit-trail-phase0-mechanism.md`) must be complete first — this plan needs `tools/generate-audit-triggers.ps1` and the `audit_trail` schema pattern to already exist. Does **not** depend on Phase 1 (Payroll) or Phase 2 (Facturation) — all three are independent, just sequenced after Phase 0.

**Reference:** `daf360-sql/docs/superpowers/specs/2026-11-02-generic-audit-trail-design.md` (design), `daf360-sql/docs/AUDIT-TRAIL-ACTOR-CAPTURE.md` (actor-capture pattern, produced by Phase 0 Task 5).

**Version numbers used:** `V125__audit_trail.sql`, `V126__audit_triggers.sql` in `daf360-sql/db-rh/migration/`. Confirmed free as of 2026-11-03 — the folder's tip was `V121__document_completion_and_sharepoint_sync.sql`. **Re-check this before starting** (`ls db-rh/migration/*.sql | sort -V | tail -5` in `daf360-sql`) in case Phase 0 or anything else added files in between — if V125/V126 are taken, use the next free numbers and update this plan's header.

**Exclusion-list decision: none.** RH has four existing mechanisms that could plausibly justify excluding a table (`audit_log`, `historique_contrat_collaborateur`, `regime_assignment_history`, `employee_lifecycle_transitions`/`mission_status_history`), but per the design spec's strict rule — exclude only if an existing mechanism captures *every column* on *every write* — none of them qualify:
- `audit_log` is event/call-site-driven (populated only where a service explicitly calls it), not a full-row trigger-equivalent for any one source table.
- `historique_contrat_collaborateur` captures only `id_type_contrat`/`date_effet`/`date_fin`/`salaire_net`/`motif` — a handful of fields, not every column of `employee_contracts` or `employee_profiles`.
- `regime_assignment_history` captures only `old_regime_id`/`new_regime_id`/effective dates — not every column of whatever table assigns regimes.
- `employee_lifecycle_transitions`/`mission_status_history` capture only a status-transition pair (`statut_avant`/`statut_apres`, `from_status`/`to_status`) — not full rows of `employee_contracts`/`missions`.

So every RH table gets the generic trigger. The `-Exclude` flag is available but unused here — pass `-Exclude` with no arguments (i.e. omit it) when running the generator.

---

### Task 1: Baseline the local HR database's script ledger, then create `audit_trail`

**Why baseline first:** same reason as Phase 0's Payroll pilot — `DAF360_HR`'s `script_ledger` has never been initialized (confirmed 2026-11-03: all 141 existing migrations show `PENDING`, plus `NEVER=1` for the hardcoded-excluded `V94__users_account_type.sql`). Applying anything new without baselining first would try to replay all 141 files from `V2` onward.

**Files:**
- Create: `daf360-sql/db-rh/migration/V125__audit_trail.sql`

- [ ] **Step 1: Preview baseline**

```powershell
cd C:\Users\ITEC2\OneDrive\Documents\projects\daf360-sql
.\apply.ps1 -Baseline -Db hr -WhatIf
```
Expected: most/all of the 141 files proposed as `BASELINE`, `V94__users_account_type.sql` proposed as `EXCLUDED` (matching `apply.ps1`'s hardcoded `never` list for HR), `WhatIf: nothing was recorded.` If `V94` doesn't show `EXCLUDED` here, or if more than a handful of real files show as staying `PENDING`, stop and investigate the fingerprint markers (`tools/markers/hr_cut.sql`, `tools/markers/hr.sql`) before continuing — don't push through a baseline that misclassifies an already-applied migration.

- [ ] **Step 2: Run baseline for real**

```powershell
.\apply.ps1 -Baseline -Db hr -Yes
```
Expected: `DAF360_HR: <N> row(s) recorded` matching the preview.

- [ ] **Step 3: Verify**

```powershell
.\apply.ps1 -Status -Db hr
```
Expected: no `no script_ledger yet` warning. `V94__users_account_type.sql` shows `NEVER` or is absent from the pending list (excluded).

- [ ] **Step 4: Write the `audit_trail` migration**

Identical schema to Payroll's (Phase 0 Task 1), only the header comment differs:

```sql
-- V125__audit_trail.sql
-- Generic audit trail: one row per INSERT/UPDATE/DELETE on any in-scope table,
-- written by auto-generated per-table triggers (tools/generate-audit-triggers.ps1).
-- See docs/superpowers/specs/2026-11-02-generic-audit-trail-design.md.

IF OBJECT_ID('dbo.audit_trail') IS NULL
BEGIN
    CREATE TABLE dbo.audit_trail (
        id          BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_audit_trail PRIMARY KEY,
        table_name  SYSNAME           NOT NULL,
        pk_json     NVARCHAR(400)     NOT NULL,
        action      CHAR(1)           NOT NULL CONSTRAINT CK_audit_trail_action CHECK (action IN ('I','U','D')),
        old_value   NVARCHAR(MAX)     NULL,
        new_value   NVARCHAR(MAX)     NULL,
        changed_by  NVARCHAR(200)     NULL,
        changed_at  DATETIME2         NOT NULL CONSTRAINT DF_audit_trail_changed_at DEFAULT SYSUTCDATETIME()
    );
    CREATE NONCLUSTERED INDEX IX_audit_trail_table_pk ON dbo.audit_trail(table_name, pk_json);
    CREATE NONCLUSTERED INDEX IX_audit_trail_changed_at ON dbo.audit_trail(changed_at);
    PRINT 'audit_trail created.';
END
GO
```

- [ ] **Step 5: Apply and verify**

```powershell
.\apply.ps1 -Apply -Db hr -WhatIf   # expect: plan shows exactly V125__audit_trail.sql
.\apply.ps1 -Apply -Db hr -Yes      # expect: APPLIED  hr  V125__audit_trail.sql
.\apply.ps1 -Status -Db hr          # expect: V125 now APPLIED
```

- [ ] **Step 6: Commit**

```bash
git add db-rh/migration/V125__audit_trail.sql
git commit -m "feat(hr): add audit_trail table for generic audit mechanism

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Generate and apply triggers for every RH table

**Files:**
- Create: `daf360-sql/db-rh/migration/V126__audit_triggers.sql`

- [ ] **Step 1: Dry-run the generator**

```powershell
.\tools\generate-audit-triggers.ps1 -Db hr -OutFile db-rh\migration\V126__audit_triggers.sql -WhatIf
```
Expected: `Tables in scope:` around 60 (61 `CREATE TABLE` statements counted in migration files as of 2026-11-02, minus any tables dropped since, minus `audit_trail`/`script_ledger` which the generator already excludes by name). `Excluded (-Exclude): 0` (per this plan's "no exclusions" decision above). Review the `SKIPPED (no primary key found...)` line carefully — if any real table appears there, do NOT proceed silently; note which table(s) and decide by hand whether it genuinely has no PK (rare, likely a pure join/bridge table) before continuing, since that table will need separate manual handling outside this plan's scope.

- [ ] **Step 2: Generate for real and apply**

```powershell
.\tools\generate-audit-triggers.ps1 -Db hr -OutFile db-rh\migration\V126__audit_triggers.sql
.\apply.ps1 -Apply -Db hr -WhatIf   # expect: plan shows exactly V126__audit_triggers.sql
.\apply.ps1 -Apply -Db hr -Yes      # expect: APPLIED  hr  V126__audit_triggers.sql
```

- [ ] **Step 3: Spot-check with a real write**

Pick `contract_types` (small, simple, single-column PK, seeded in `V103__backfill_contract_types_baseline.sql`) and verify the same way as Phase 0's Task 3 Step 3 (adapt the table/column names and database to `DAF360_HR`):

```powershell
$verify = @'
SET NOCOUNT ON;
DELETE FROM contract_types WHERE id = 999999;
DELETE FROM dbo.audit_trail WHERE table_name = 'contract_types' AND pk_json LIKE '%999999%';

EXEC sys.sp_set_session_context @key = N'app_user', @value = N'phase3-test-user';

INSERT INTO contract_types (id, code, label) VALUES (999999, 'ZZTEST', 'Test');
UPDATE contract_types SET label = 'Test2' WHERE id = 999999;
DELETE FROM contract_types WHERE id = 999999;

SELECT action, pk_json, old_value, new_value, changed_by
FROM dbo.audit_trail
WHERE table_name = 'contract_types' AND pk_json LIKE '%999999%'
ORDER BY id;
'@
Set-Content -Path "$env:TEMP\verify_hr_audit.sql" -Value $verify -Encoding UTF8
$pw = (Select-String -Path ..\.env.dev -Pattern '^DB_PASSWORD=').Line -replace '^DB_PASSWORD=', ''
docker cp "$env:TEMP\verify_hr_audit.sql" timesheet-sqlserver:/tmp/verify_hr_audit.sql
docker exec -e "SQLCMDPASSWORD=$pw" timesheet-sqlserver bash -c '/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -I -b -d DAF360_HR -i /tmp/verify_hr_audit.sql'
```
Expected: 3 rows (`I`/`U`/`D`), `changed_by='phase3-test-user'` on all three, matching Phase 0's verified pattern exactly. **If `contract_types` doesn't actually have columns `id`/`code`/`label`, check `db-rh/migration/V103__backfill_contract_types_baseline.sql` for the real column list and adjust the INSERT/UPDATE statements accordingly before running this.**

- [ ] **Step 4: Commit**

```bash
git add db-rh/migration/V126__audit_triggers.sql
git commit -m "feat(hr): generate audit triggers for every RH table

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Wire actor capture into `daf360-rh-service`

RH has no explicit `DataSource` `@Bean` today (Spring Boot autoconfigures one from `application.yml`'s `spring.datasource.*` properties) — confirmed via search of `src/main/java/com/daf360/rh/config/`, only `JacksonConfig.java` and `JpaAuditingConfig.java` exist there, neither touches `DataSource`. `JpaAuditingConfig.java` already has the exact username-resolution logic needed (`SecurityContextHolder.getContext().getAuthentication()`), reused below instead of duplicated.

**Files:**
- Create: `src/main/java/com/daf360/rh/config/SessionContextDataSource.java`
- Create: `src/main/java/com/daf360/rh/config/AuditDataSourceConfig.java`

- [ ] **Step 1: Copy the reference class from `daf360-sql/docs/AUDIT-TRAIL-ACTOR-CAPTURE.md`**

```java
package com.daf360.rh.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the real DataSource so every borrowed connection has SESSION_CONTEXT('app_user')
 * set before any statement runs on it — read by the generic audit triggers
 * (tools/generate-audit-triggers.ps1 in daf360-sql). Writes made outside the app
 * (ad-hoc SQL scripts) simply leave this NULL.
 */
public class SessionContextDataSource extends DelegatingDataSource {

    public SessionContextDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withSessionContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withSessionContext(super.getConnection(username, password));
    }

    private Connection withSessionContext(Connection connection) throws SQLException {
        try (CallableStatement cs = connection.prepareCall("{call sys.sp_set_session_context(?, ?)}")) {
            cs.setString(1, "app_user");
            cs.setString(2, currentUsername());
            cs.execute();
        }
        return connection;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
    }
}
```

- [ ] **Step 2: Wrap the autoconfigured DataSource**

Spring Boot's autoconfigured `DataSource` bean is named `dataSource`. Wrap it with a `@Primary` bean of the same type so JPA/JDBC pick up the wrapped one everywhere without any other config change:

```java
package com.daf360.rh.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class AuditDataSourceConfig {

    @Bean
    @Primary
    public DataSource auditedDataSource(DataSource dataSource) {
        return new SessionContextDataSource(dataSource);
    }
}
```

- [ ] **Step 3: Verify via a real write path**

Start the service locally (`docker compose` per the existing dev workflow), log in, and make any write that already exists in the app — e.g. update your own profile's phone number via the RH frontend, or create an onboarding record. Then check:

```powershell
cd C:\Users\ITEC2\OneDrive\Documents\projects\daf360-sql
$pw = (Select-String -Path ..\.env.dev -Pattern '^DB_PASSWORD=').Line -replace '^DB_PASSWORD=', ''
docker exec -e "SQLCMDPASSWORD=$pw" timesheet-sqlserver bash -c "/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -I -b -d DAF360_HR -Q \"SELECT TOP 5 table_name, action, changed_by, changed_at FROM dbo.audit_trail ORDER BY id DESC\""
```
Expected: the most recent row's `changed_by` shows your real logged-in username (e.g. your email or user id, matching whatever `Authentication.getName()` returns in this app), not `NULL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/daf360/rh/config/SessionContextDataSource.java src/main/java/com/daf360/rh/config/AuditDataSourceConfig.java
git commit -m "feat(audit): wire SESSION_CONTEXT actor capture for the generic audit trail

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Merge `audit_log` and `audit_trail` into one backend endpoint

Today `AuditController.logs()` returns `Page<AuditLog>` straight from `auditLogRepository.findAll(pageable)` — only the old table. This task replaces that with a merged view, keeping `AuditLogRepository` itself untouched (it's still used elsewhere, e.g. `findByEntityTypeAndEntityIdInOrderByTimestampDesc` for the offboarding audit drawer).

A true SQL-level `UNION ALL` with `OFFSET`/`FETCH` is used instead of fetching both sources and merging in memory — an in-memory merge can't correctly serve page 2+ without first re-fetching and re-sorting everything from page 0, which would be both wrong and slow.

**Files:**
- Create: `src/main/java/com/daf360/rh/repository/AuditEntryView.java`
- Create: `src/main/java/com/daf360/rh/repository/AuditQueryRepository.java`
- Create: `src/main/java/com/daf360/rh/dto/AuditEntryDto.java`
- Modify: `src/main/java/com/daf360/rh/controller/AuditController.java`

- [ ] **Step 1: Write the projection interface**

```java
package com.daf360.rh.repository;

import java.time.OffsetDateTime;

/** One row of the merged audit_log + audit_trail timeline — see AuditQueryRepository. */
public interface AuditEntryView {
    Long getId();
    String getSource();
    String getActor();
    String getAction();
    String getEntityType();
    String getEntityId();
    String getOldValue();
    String getNewValue();
    String getIp();
    OffsetDateTime getTs();
}
```

- [ ] **Step 2: Write the native-query repository**

```java
package com.daf360.rh.repository;

import com.daf360.rh.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Merges the event-driven, call-site-populated [audit_log] with the generic,
 * trigger-written [audit_trail] into one timeline for AuditController.
 * Declared against AuditLog as its nominal entity type only because Spring Data
 * repositories need one — this repository's actual query spans both tables.
 */
public interface AuditQueryRepository extends Repository<AuditLog, Long> {

    @Query(value = """
        SELECT * FROM (
            SELECT id,
                   CAST('EVENT_LOG' AS VARCHAR(10)) AS source,
                   CAST(userId AS NVARCHAR(200)) AS actor,
                   CAST(action AS NVARCHAR(50)) AS action,
                   CAST(entityType AS NVARCHAR(100)) AS entityType,
                   CAST(entityId AS NVARCHAR(400)) AS entityId,
                   oldValue, newValue,
                   CAST(ipAddress AS NVARCHAR(50)) AS ip,
                   timestamp AS ts
            FROM audit_log
            UNION ALL
            SELECT id,
                   CAST('GENERIC' AS VARCHAR(10)) AS source,
                   CAST(changed_by AS NVARCHAR(200)) AS actor,
                   CAST(CASE action WHEN 'I' THEN 'CREATE' WHEN 'U' THEN 'UPDATE' WHEN 'D' THEN 'DELETE' ELSE action END AS NVARCHAR(50)) AS action,
                   CAST(table_name AS NVARCHAR(100)) AS entityType,
                   CAST(pk_json AS NVARCHAR(400)) AS entityId,
                   old_value AS oldValue, new_value AS newValue,
                   CAST(NULL AS NVARCHAR(50)) AS ip,
                   CAST(changed_at AS DATETIMEOFFSET) AS ts
            FROM audit_trail
        ) combined
        ORDER BY ts DESC
        """,
        countQuery = "SELECT (SELECT COUNT(*) FROM audit_log) + (SELECT COUNT(*) FROM audit_trail)",
        nativeQuery = true)
    Page<AuditEntryView> findMerged(Pageable pageable);
}
```

- [ ] **Step 3: Write the response DTO**

```java
package com.daf360.rh.dto;

import com.daf360.rh.repository.AuditEntryView;

import java.time.OffsetDateTime;

public record AuditEntryDto(
        Long id,
        String source,
        String actor,
        String action,
        String entityType,
        String entityId,
        String oldValue,
        String newValue,
        String ip,
        OffsetDateTime timestamp
) {
    public static AuditEntryDto from(AuditEntryView v) {
        return new AuditEntryDto(v.getId(), v.getSource(), v.getActor(), v.getAction(),
                v.getEntityType(), v.getEntityId(), v.getOldValue(), v.getNewValue(), v.getIp(), v.getTs());
    }
}
```

- [ ] **Step 4: Update the controller**

```java
package com.daf360.rh.controller;

import com.daf360.rh.dto.AuditEntryDto;
import com.daf360.rh.repository.AuditQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryRepository auditQueryRepository;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public Page<AuditEntryDto> logs(Pageable pageable) {
        return auditQueryRepository.findMerged(pageable).map(AuditEntryDto::from);
    }
}
```

Remove the now-unused `AuditLogRepository` field/import from this file (the repository itself stays — it's still used by other controllers/services).

- [ ] **Step 5: Verify**

Start the service, call `GET /api/hr/audit/logs?page=0&size=20` with a valid auth token, and confirm the response contains entries from both `source: "EVENT_LOG"` and `source: "GENERIC"`, sorted by `timestamp` descending across both. Check page 1 (`?page=1`) also returns correctly-ordered, non-duplicated rows relative to page 0 — this is the specific correctness property an in-memory merge would risk getting wrong.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/daf360/rh/repository/AuditEntryView.java src/main/java/com/daf360/rh/repository/AuditQueryRepository.java src/main/java/com/daf360/rh/dto/AuditEntryDto.java src/main/java/com/daf360/rh/controller/AuditController.java
git commit -m "feat(audit): merge audit_log and audit_trail into one paged timeline

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Give `AuditLogComponent` a real route and nav entry

Today it has neither — confirmed no route references `features/audit` anywhere and no nav entry in `hr-shell.component.ts`. This follows the exact same pattern as the existing `it-provisioning/historique` and `missions/historique` entries (a `<module>.routes.ts` file lazy-loaded from `app.routes.ts`, plus a nav entry in `hr-shell.component.ts`'s `APP_NAV_DEFS`).

**Files:**
- Create: `src/app/features/audit/audit.routes.ts`
- Modify: `src/app/app.routes.ts`
- Modify: `src/app/layout/hr-shell.component.ts`
- Modify: `public/assets/i18n/en.json`, `public/assets/i18n/fr.json`, `public/assets/i18n/ar.json`

- [ ] **Step 1: Create the route file**

```typescript
import { Routes } from '@angular/router';

/** `/rh/audit` — merged audit_log + audit_trail timeline. Gated in app.routes. */
export const AUDIT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./audit-log.component').then(m => m.AuditLogComponent),
  },
];
```

- [ ] **Step 2: Register it in `app.routes.ts`**

Add alongside the other `loadChildren` entries (same block as `missions`/`requests-history`):

```typescript
      {
        path: 'audit',
        canActivate: [permissionGuard],
        data: { permissions: ['HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE'] },
        loadChildren: () =>
          import('./features/audit/audit.routes').then(m => m.AUDIT_ROUTES),
      },
```

- [ ] **Step 3: Add the nav entry**

In `hr-shell.component.ts`'s `APP_NAV_DEFS` array, add a top-level entry (no `children`, matching the `accueil`/`profiles` shape since this is a single page, not a current+history pair):

```typescript
  {
    id: 'audit',
    labelKey: 'NAV.AUDIT',
    icon: 'history',
    route: 'audit',
    permissions: ['HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE'],
  },
```

- [ ] **Step 4: Add the i18n key to all three locale files**

In `public/assets/i18n/en.json`, inside the `NAV` object (alongside `IT_PROVISIONING_HISTORY` etc.):
```json
    "AUDIT": "Audit Log",
```
In `fr.json`:
```json
    "AUDIT": "Journal d'audit",
```
In `ar.json`, add the equivalent Arabic translation following that file's existing style for other `NAV.*` entries (check a neighboring key in `ar.json` for the exact RTL phrasing convention used there before picking the exact wording).

- [ ] **Step 5: Verify**

Run `npm run build` (or `ng build --configuration development`), confirm it compiles. Start the app, log in as a user with `HR_ADMIN_ROLES`, confirm an "Audit Log" nav entry now appears and navigates to a working page.

- [ ] **Step 6: Commit**

```bash
git add src/app/features/audit/audit.routes.ts src/app/app.routes.ts src/app/layout/hr-shell.component.ts public/assets/i18n/en.json public/assets/i18n/fr.json public/assets/i18n/ar.json
git commit -m "feat(audit): give AuditLogComponent a real route and nav entry

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Update the frontend to render the merged data

**Files:**
- Modify: `src/app/core/models/audit.model.ts`
- Modify: `src/app/features/audit/audit-log.component.ts`

- [ ] **Step 1: Update the model to match the new DTO shape**

```typescript
export interface AuditLog {
  id: number;
  source: 'EVENT_LOG' | 'GENERIC';
  actor: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  oldValue: string | null;
  newValue: string | null;
  ip: string | null;
  timestamp: string;
}
```

- [ ] **Step 2: Update the component's template and field references**

Replace the old field names (`actorId`→`actor`, `entity`→`entityType`, `createdAt`→`timestamp`) and add a `source` badge column. `GENERIC` rows have no `ip` — the existing `?? '—'` fallback already handles that correctly, no new logic needed there. The old `log.id` alone is not a safe `track` key any more since `audit_log` and `audit_trail` each restart their own identity from 1, so two rows from different sources can share the same `id` — track by `source + id` instead:

```typescript
import { Component, OnInit, signal } from '@angular/core';
import { AuditService } from '../../core/services/audit.service';
import { AuditLog } from '../../core/models/audit.model';
import { Page } from '../../core/models/page.model';

@Component({
  selector: 'app-audit-log',
  standalone: true,
  template: `
    <div class="page-header">
      <div>
        <h2 class="page-title">Audit Log</h2>
        <p class="page-subtitle">Full history of HR actions — business events and raw field changes</p>
      </div>
    </div>

    @if (error()) { <div class="alert alert-error">{{ error() }}</div> }

    <div class="card">
      @if (loading()) {
        <div class="spinner-wrap"><div class="spinner"></div></div>
      } @else if (!page() || page()!.content.length === 0) {
        <div class="empty-state"><p>No audit entries found.</p></div>
      } @else {
        <div class="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Source</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Entity</th>
                <th>Entity ID</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              @for (log of page()!.content; track log.source + ':' + log.id) {
                <tr>
                  <td class="text-muted mono">{{ log.timestamp.slice(0,16).replace('T',' ') }}</td>
                  <td>
                    <span class="badge" [class.badge-dark]="log.source === 'EVENT_LOG'">
                      {{ log.source === 'EVENT_LOG' ? 'Event' : 'Field change' }}
                    </span>
                  </td>
                  <td>
                    <span class="mono" style="font-size:0.75rem">{{ log.actor ?? '—' }}</span>
                  </td>
                  <td>
                    <span class="badge badge-dark">{{ log.action }}</span>
                  </td>
                  <td>{{ log.entityType }}</td>
                  <td class="mono">{{ log.entityId ?? '—' }}</td>
                  <td class="text-muted">{{ log.ip ?? '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (page()!.totalPages > 1) {
          <div class="pagination">
            <span class="page-info">
              Page {{ page()!.number + 1 }} of {{ page()!.totalPages }} —
              {{ page()!.totalElements }} entries
            </span>
            <button class="page-btn" [disabled]="page()!.first"  (click)="load(page()!.number - 1)">‹</button>
            <button class="page-btn" [disabled]="page()!.last"   (click)="load(page()!.number + 1)">›</button>
          </div>
        }
      }
    </div>
  `,
})
export class AuditLogComponent implements OnInit {
  page    = signal<Page<AuditLog> | null>(null);
  loading = signal(true);
  error   = signal('');

  constructor(private auditService: AuditService) {}

  ngOnInit(): void { this.load(0); }

  load(page: number): void {
    this.loading.set(true);
    this.auditService.logs(page).subscribe({
      next:  (p) => { this.page.set(p); this.loading.set(false); },
      error: (e) => { this.error.set(e.error?.message ?? 'Failed to load audit logs'); this.loading.set(false); },
    });
  }
}
```

`AuditService.logs()` itself needs no change — it already just passes through whatever the backend returns as `Page<AuditLog>`.

- [ ] **Step 3: Verify**

Run `npm run build`, confirm it compiles with no type errors (the `AuditLog` interface fields must match exactly what the template binds). Reload the page from Task 5 and confirm both `Event` and `Field change` rows render with sensible values, and that paging still works.

- [ ] **Step 4: Commit**

```bash
git add src/app/core/models/audit.model.ts src/app/features/audit/audit-log.component.ts
git commit -m "feat(audit): render merged audit_log/audit_trail entries in AuditLogComponent

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:** audit_trail + triggers for every RH table ✓ (Tasks 1–2), actor capture wired ✓ (Task 3), backend merge of old + new sources ✓ (Task 4), orphaned screen revived with nav entry ✓ (Task 5), frontend renders both sources ✓ (Task 6). Exclusion-list reasoning documented and justified against the spec's strict rule, not assumed.

**Placeholder scan:** no TBD/TODO. One deliberately-flagged manual-check point (Task 2 Step 3's `contract_types` column names, Task 5 Step 4's `ar.json` wording) — both are explicit "verify before using" instructions, not unresolved placeholders, since the real answer depends on live file content the implementer must check at execution time.

**Type/name consistency:** `AuditEntryDto`'s field names (`id/source/actor/action/entityType/entityId/oldValue/newValue/ip/timestamp`) are identical across `AuditEntryView` (Task 4 Step 1), the DTO (Step 3), the controller (Step 4), and the frontend `AuditLog` model (Task 6 Step 1) — this consistency is exactly what Task 6's build-verification step checks for. `audit_trail` schema matches Phase 0's exactly (table_name/pk_json/action/old_value/new_value/changed_by/changed_at). `SessionContextDataSource` is a verbatim copy of the Phase 0 reference with only the package name changed, as instructed.

**Known limitation, not fixed here:** the merged query's `id` column is only unique *within* a source (two different `audit_log`/`audit_trail` rows can share the same numeric id) — handled correctly in the frontend via a composite `source + ':' + id` track key, but any future consumer of `AuditEntryDto.id()` alone (e.g. a "link to this entry" deep-link feature) would need the same composite-key treatment, not just the raw `id`.
