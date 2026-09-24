# Contract Types Reference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace four independently drifted hardcoded contract-type lists (rh-service's own free-text domain, and three separate hardcoded frontend arrays across rh-frontend and payroll-frontend) with a single per-country, database-backed reference list in rh-service, consumed dynamically everywhere.

**Architecture:** New `contract_types` table + `ContractType` entity/repository in `daf360-rh-service`, exposed through the existing `ReferenceDataService`/`ReferenceDataController` (the same pattern already used for Grades, Disciplines, Banks, etc. — read-only for this feature, no create/delete). Three frontend consumers (rh-frontend's `RemunerationSectionComponent`, payroll-frontend's `employee-config.component.ts` and `parameter-sets.component.ts`) fetch this list per-country instead of hardcoding their own copy.

**Tech Stack:** Spring Boot / JPA / SQL Server (backend), Angular standalone components with signals and Reactive Forms (frontend).

**Reference:** `docs/superpowers/specs/2026-09-24-contract-types-reference-design.md` (this repo) — read it first for full rationale (why per-country, why not reusing `ContractTypeConfig`, the exact seed table).

**Scope note:** this plan spans THREE repos:
- `daf360-rh-service` — Tasks 1–3 (backend: new table, entity, repository, service method, endpoint)
- `daf360-rh-frontend` — Task 4
- `daf360-payroll-frontend` — Tasks 5–6
- Task 7 (frontend build verification) touches both frontend repos; Task 8 (final review) touches all three.

**Standing rule for this work:** do NOT run `git add`, `git commit`, or `git push` in any of the three repos for any task in this plan (the design-spec doc in `daf360-rh-service` is already committed as a deliberate, separate exception — leave it as is, do not re-touch it). Every task ends with a "confirm scope via `git status`/`git diff`, DO NOT STAGE OR COMMIT" step.

---

### Task 1: `contract_types` table, entity, repository (daf360-rh-service)

**Files:**
- Create: `src/main/resources/db/seed/V102__contract_types.sql` (version number confirmed against the real directory at execution time — `V101__add_personal_phone_column.sql` is the current latest; if a newer migration exists by execution time, use the next free number instead and rename accordingly throughout this task)
- Create: `src/main/java/com/daf360/rh/domain/ContractType.java`
- Create: `src/main/java/com/daf360/rh/repository/ContractTypeRepository.java`

- [ ] **Step 1: Re-verify the next migration version number**

Run: `ls src/main/resources/db/seed | sort -V | tail -5` (from the `daf360-rh-service` repo root)
Expected: confirms `V101__add_personal_phone_column.sql` is still the latest. If not, use the next free number in place of `V102` for the rest of this task.

- [ ] **Step 2: Create the migration file**

```sql
-- V102__contract_types.sql
-- Per-country contract-type reference list, replacing four independently hardcoded
-- lists across rh-service/rh-frontend/payroll-service/payroll-frontend. See
-- docs/superpowers/specs/2026-09-24-contract-types-reference-design.md.

CREATE TABLE contract_types (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    pays_id       BIGINT NOT NULL,
    code          NVARCHAR(30) NOT NULL,
    label_fr      NVARCHAR(100) NOT NULL,
    label_en      NVARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_active     BIT NOT NULL DEFAULT 1,
    created_at    DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT UQ_contract_types_pays_code UNIQUE (pays_id, code)
);

CREATE INDEX IX_contract_types_pays_active ON contract_types(pays_id, is_active);

-- Tunisia (179) — all 11 known codes across both onboarding-wizard generations and
-- legacy employee_profiles.contract_type values.
INSERT INTO contract_types (pays_id, code, label_fr, label_en, display_order) VALUES
(179, 'CDI',         N'CDI',                  'Permanent contract',   1),
(179, 'CDD',         N'CDD',                  'Fixed-term contract',  2),
(179, 'CIVP',        N'CIVP',                 'CIVP (Tunisia)',       3),
(179, 'STAGE',       N'Stage',                'Internship',           4),
(179, 'FREELANCE',   N'Freelance',            'Freelance',            5),
(179, 'DETACHEMENT', N'Détachement',          'Secondment',           6),
(179, 'PORTAGE',     N'Portage salarial',     'Umbrella employment',  7),
(179, 'PERMANENT',   N'Permanent',            'Permanent',            8),
(179, 'FIXED_TERM',  N'Durée déterminée',     'Fixed term',           9),
(179, 'INTERN',      N'Stagiaire',            'Intern',              10),
(179, 'CONSULTANT',  N'Consultant',           'Consultant',          11);

-- Egypt (53) — same list minus CIVP (Tunisia-specific ANETI scheme, see design doc).
INSERT INTO contract_types (pays_id, code, label_fr, label_en, display_order) VALUES
(53, 'CDI',         N'CDI',                  'Permanent contract',   1),
(53, 'CDD',         N'CDD',                  'Fixed-term contract',  2),
(53, 'STAGE',       N'Stage',                'Internship',           3),
(53, 'FREELANCE',   N'Freelance',            'Freelance',            4),
(53, 'DETACHEMENT', N'Détachement',          'Secondment',           5),
(53, 'PORTAGE',     N'Portage salarial',     'Umbrella employment',  6),
(53, 'PERMANENT',   N'Permanent',            'Permanent',            7),
(53, 'FIXED_TERM',  N'Durée déterminée',     'Fixed term',           8),
(53, 'INTERN',      N'Stagiaire',            'Intern',               9),
(53, 'CONSULTANT',  N'Consultant',           'Consultant',          10);
```

Do NOT apply this migration to the dev DB yet — that happens in Task 3, after the entity/repository compile cleanly against it.

- [ ] **Step 3: Create the entity**

```java
package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "contract_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "code", nullable = false, length = 30, columnDefinition = "nvarchar(30)")
    private String code;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
```

- [ ] **Step 4: Create the repository**

```java
package com.daf360.rh.repository;

import com.daf360.rh.domain.ContractType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ContractTypeRepository extends JpaRepository<ContractType, Long> {

    List<ContractType> findByPaysIdAndIsActiveTrueOrderByDisplayOrderAsc(Long paysId);
    List<ContractType> findByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * Several entities at once — a role in V74 LIST mode legitimately covers more than one
     * pays, and the single-id finder above keeps only one of them. Mirrors GradeRepository's
     * own overload of the same shape.
     */
    List<ContractType> findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(Collection<Long> paysIds);
}
```

- [ ] **Step 5: Compile to confirm no errors**

Run: `./mvnw.cmd compile` (from `daf360-rh-service` repo root)
Expected: `BUILD SUCCESS`. The entity/repository reference a table that doesn't exist in the DB yet — that's fine, compilation doesn't touch the database.

- [ ] **Step 6: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short`
Expected: shows the 2 new Java files and 1 new SQL file as `??` (untracked), nothing staged.

---

### Task 2: Service method + controller endpoint, TDD (daf360-rh-service)

**Files:**
- Modify: `src/main/java/com/daf360/rh/service/ReferenceDataService.java`
- Modify: `src/main/java/com/daf360/rh/controller/ReferenceDataController.java`
- Create: `src/test/java/com/daf360/rh/service/ReferenceDataServiceTest.java`

This is the first test ever written for `ReferenceDataService` (it currently has zero test coverage) — write only the two tests below, do not attempt to backfill coverage for the other dimension types.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/daf360/rh/service/ReferenceDataServiceTest.java`:

```java
package com.daf360.rh.service;

import com.daf360.rh.domain.ContractType;
import com.daf360.rh.dto.ref.RefDataItemDto;
import com.daf360.rh.repository.ContractTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link ReferenceDataService}'s contract-types methods.
 * No Spring context — all collaborators are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceDataServiceTest {

    @Mock ContractTypeRepository contractTypeRepo;

    @InjectMocks ReferenceDataService service;

    @Test
    void getContractTypes_withPaysIds_filtersByPaysAndMapsToDto() {
        ContractType cdi = ContractType.builder()
                .id(1L).paysId(179L).code("CDI").labelFr("CDI").labelEn("Permanent contract")
                .displayOrder(1).isActive(true).build();
        ContractType civp = ContractType.builder()
                .id(2L).paysId(179L).code("CIVP").labelFr("CIVP").labelEn("CIVP (Tunisia)")
                .displayOrder(3).isActive(true).build();
        when(contractTypeRepo.findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(List.of(179L)))
                .thenReturn(List.of(cdi, civp));

        List<RefDataItemDto> result = service.getContractTypes(List.of(179L));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCode()).isEqualTo("CDI");
        assertThat(result.get(0).getLabelEn()).isEqualTo("Permanent contract");
        assertThat(result.get(1).getCode()).isEqualTo("CIVP");
        verify(contractTypeRepo).findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(List.of(179L));
        verifyNoMoreInteractions(contractTypeRepo);
    }

    @Test
    void getContractTypes_nullPaysIds_fetchesUnfiltered() {
        when(contractTypeRepo.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());

        List<RefDataItemDto> result = service.getContractTypes(null);

        assertThat(result).isEmpty();
        verify(contractTypeRepo).findByIsActiveTrueOrderByDisplayOrderAsc();
        verifyNoMoreInteractions(contractTypeRepo);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw.cmd test -Dtest=ReferenceDataServiceTest` (from `daf360-rh-service` repo root)
Expected: **compile failure** — `ContractTypeRepository` has no field of that type wired into `ReferenceDataService` yet, and `getContractTypes(...)` doesn't exist on the service. This compile failure IS the RED step (there is no pre-existing method to accidentally pass against) — do not skip it.

- [ ] **Step 3: Add the repository field and service method**

In `src/main/java/com/daf360/rh/service/ReferenceDataService.java`, find (confirmed current content):

```java
    private final GradeRepository        gradeRepo;
    private final DisciplineRepository   disciplineRepo;
    private final NogLevelRepository     nogLevelRepo;
    private final HrDepartmentRepository deptRepo;
    private final BankRepository         bankRepo;
    private final NationalityRepository  natRepo;
    private final ItAssetTypeRepository  assetTypeRepo;
```

Replace with:

```java
    private final GradeRepository        gradeRepo;
    private final DisciplineRepository   disciplineRepo;
    private final NogLevelRepository     nogLevelRepo;
    private final HrDepartmentRepository deptRepo;
    private final BankRepository         bankRepo;
    private final NationalityRepository  natRepo;
    private final ItAssetTypeRepository  assetTypeRepo;
    private final ContractTypeRepository contractTypeRepo;
```

(`ContractType`/`ContractTypeRepository` are already in scope via this file's existing `import com.daf360.rh.domain.*;` and `import com.daf360.rh.repository.*;` wildcard imports — no new import lines needed.)

Find (confirmed current content, in the IT Asset Types section):

```java
    public void deleteItAssetType(Long id) {
        assetTypeRepo.findById(id).ifPresent(a -> { a.setIsActive(false); assetTypeRepo.save(a); });
    }

    private RefDataItemDto toAssetTypeDto(com.daf360.rh.domain.ItAssetType a) {
        return RefDataItemDto.builder()
                .id(a.getId()).code(a.getCode())
                .labelFr(a.getLabelFr()).labelEn(a.getLabelEn())
                .sortOrder(a.getSortOrder()).isActive(a.getIsActive())
                .build();
    }

    // ── Private mappers ───────────────────────────────────────────────────────
```

Replace with:

```java
    public void deleteItAssetType(Long id) {
        assetTypeRepo.findById(id).ifPresent(a -> { a.setIsActive(false); assetTypeRepo.save(a); });
    }

    private RefDataItemDto toAssetTypeDto(com.daf360.rh.domain.ItAssetType a) {
        return RefDataItemDto.builder()
                .id(a.getId()).code(a.getCode())
                .labelFr(a.getLabelFr()).labelEn(a.getLabelEn())
                .sortOrder(a.getSortOrder()).isActive(a.getIsActive())
                .build();
    }

    // ── Contract Types (per-country; read-only — no admin UI yet, seeded via migration) ──

    @Transactional(readOnly = true)
    public List<RefDataItemDto> getContractTypes(Collection<Long> paysIds) {
        var list = (paysIds != null)
                ? contractTypeRepo.findByPaysIdInAndIsActiveTrueOrderByDisplayOrderAsc(paysIds)
                : contractTypeRepo.findByIsActiveTrueOrderByDisplayOrderAsc();
        return list.stream().map(this::toContractTypeDto).collect(Collectors.toList());
    }

    private RefDataItemDto toContractTypeDto(ContractType c) {
        return RefDataItemDto.builder()
                .id(c.getId()).paysId(c.getPaysId())
                .code(c.getCode()).labelFr(c.getLabelFr()).labelEn(c.getLabelEn())
                .sortOrder(c.getDisplayOrder()).isActive(c.getIsActive())
                .build();
    }

    // ── Private mappers ───────────────────────────────────────────────────────
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw.cmd test -Dtest=ReferenceDataServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0` — PASS.

- [ ] **Step 5: Add the controller endpoint**

In `src/main/java/com/daf360/rh/controller/ReferenceDataController.java`, find (confirmed current content):

```java
    @DeleteMapping("/it-asset-types/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteItAssetType(@PathVariable Long id) {
        refService.deleteItAssetType(id);
        return ResponseEntity.noContent().build();
    }

    // ── Entity (pays) timezones ───────────────────────────────────────────────
```

Replace with:

```java
    @DeleteMapping("/it-asset-types/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteItAssetType(@PathVariable Long id) {
        refService.deleteItAssetType(id);
        return ResponseEntity.noContent().build();
    }

    // ── Contract Types (per-country; read-only — no admin UI yet) ────────────

    @GetMapping("/contract-types")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getContractTypes(@RequestParam(required = false) Long paysId) {
        return refService.getContractTypes(readableP(paysId));
    }

    // ── Entity (pays) timezones ───────────────────────────────────────────────
```

- [ ] **Step 6: Full module compile + test run**

Run: `./mvnw.cmd clean test` (from `daf360-rh-service` repo root)
Expected: `BUILD SUCCESS`, no new failures beyond any pre-existing ones already in the suite before this task started (if you see failures, confirm via `git stash` whether they predate this task's changes before treating them as caused by this work).

- [ ] **Step 7: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short` and `git diff --stat`
Expected: only `ReferenceDataService.java`, `ReferenceDataController.java` modified, plus the new test file untracked. Nothing staged.

---

### Task 3: Apply migration to dev DB + full verification (daf360-rh-service)

Pure verification/deployment task — no code changes. Dispatch as a single-pass verification agent (report DONE or DONE_WITH_CONCERNS), no separate reviewer needed.

- [ ] **Step 1: Apply the migration by hand against the real dev DB**

This dev DB does not auto-run these migrations (no Flyway wiring found in any `application*.yml` in this repo — confirmed by grep before this plan was written). Apply `V102__contract_types.sql` (or whatever version number Task 1 actually used) by hand, following this session's established pattern: a small standalone Java program using the vendored `mssql-jdbc` driver (`C:\Users\ITEC2\.m2\repository\com\microsoft\sqlserver\mssql-jdbc\12.10.2.jre11\mssql-jdbc-12.10.2.jre11.jar`), reading the DB username/password by parsing `src/main/resources/application-local.yml` at runtime (same regex-based extraction used earlier this session for `daf360-portal`'s equivalent file) — never hardcode or print the password. Target: `jdbc:sqlserver://localhost:1433;databaseName=DAF360_HR;encrypt=false;trustServerCertificate=true`.

Read the full SQL file's content and execute each statement (the `CREATE TABLE`, the `CREATE INDEX`, and both `INSERT` blocks) via JDBC `Statement.execute()`.

- [ ] **Step 2: Verify the seed data landed correctly**

Run a query confirming: `SELECT pays_id, COUNT(*) FROM contract_types GROUP BY pays_id` returns exactly `179 → 11` and `53 → 10`, and `SELECT code FROM contract_types WHERE pays_id = 53 AND code = 'CIVP'` returns zero rows (confirms CIVP correctly excluded for Egypt).

- [ ] **Step 3: Full backend build and test**

Run: `./mvnw.cmd clean test` (from `daf360-rh-service` repo root)
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short`
Expected: unchanged from Task 2's end state (this task only touched the live DB, not the working tree).

Report DONE with: the exact row counts confirmed per country, and confirmation the full test suite passes.

---

### Task 4: rh-frontend consumption (daf360-rh-frontend)

**Files:**
- Modify: `src/app/core/ref/ref-data.service.ts`
- Modify: `src/app/modules/profiles/detail-sections/remuneration-section.component.ts`

No backend changes in this repo — `RefDataItem` (in `src/app/core/ref/ref-data.model.ts`) already has every field needed (`id`, `paysId`, `code`, `labelFr`, `labelEn`, `sortOrder`, `isActive`); do not modify that file.

- [ ] **Step 1: Add `getContractTypes` to `RefDataService`**

In `src/app/core/ref/ref-data.service.ts`, find (confirmed current content):

```typescript
  getBanks(paysId?: number): Observable<RefDataItem[]> {
    const key = paysId ? `banks_${paysId}` : 'banks_all';
    const url = paysId ? `${this.base}/banks?paysId=${paysId}` : `${this.base}/banks`;
    return this.cached(key, this.http.get<RefDataItem[]>(url).pipe(catchError(() => of([]))));
  }
```

Replace with:

```typescript
  getBanks(paysId?: number): Observable<RefDataItem[]> {
    const key = paysId ? `banks_${paysId}` : 'banks_all';
    const url = paysId ? `${this.base}/banks?paysId=${paysId}` : `${this.base}/banks`;
    return this.cached(key, this.http.get<RefDataItem[]>(url).pipe(catchError(() => of([]))));
  }

  getContractTypes(paysId?: number): Observable<RefDataItem[]> {
    const key = paysId ? `contract_types_${paysId}` : 'contract_types_all';
    const url = paysId ? `${this.base}/contract-types?paysId=${paysId}` : `${this.base}/contract-types`;
    return this.cached(key, this.http.get<RefDataItem[]>(url).pipe(catchError(() => of([]))));
  }
```

- [ ] **Step 2: Rework `RemunerationSectionComponent`**

In `src/app/modules/profiles/detail-sections/remuneration-section.component.ts`, find (confirmed current content):

```typescript
import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ButtonComponent, FormFieldComponent } from '@khalilrebhiitec/daf360';

import { SectionCardComponent } from '../../../shared/detail/section-card.component';
import { EmployeeProfile } from '../models/profile.model';
import {
  BenefitCatalogueDto, EmployeePayrollBonusDto, EmployeePayrollConfigDto, RemunerationService,
} from '../services/remuneration.service';

// This app's own real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list payroll-service's
// own simulation endpoints' comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

Replace with:

```typescript
import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ButtonComponent, FormFieldComponent } from '@khalilrebhiitec/daf360';

import { SectionCardComponent } from '../../../shared/detail/section-card.component';
import { EmployeeProfile } from '../models/profile.model';
import { RefDataService } from '../../../core/ref/ref-data.service';
import { RefDataItem } from '../../../core/ref/ref-data.model';
import {
  BenefitCatalogueDto, EmployeePayrollBonusDto, EmployeePayrollConfigDto, RemunerationService,
} from '../services/remuneration.service';

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

Find (confirmed current content, in the inline template):

```html
              @for (ct of contractTypes; track ct) {
                <option [value]="ct">{{ ct }}</option>
              }
```

Replace with:

```html
              @for (ct of contractTypes(); track ct.code) {
                <option [value]="ct.code">{{ ct.code }}</option>
              }
```

Find (confirmed current content):

```typescript
export class RemunerationSectionComponent implements OnInit {
  private svc = inject(RemunerationService);
  private translate = inject(TranslateService);

  readonly profile = input.required<EmployeeProfile>();

  readonly contractTypes = CONTRACT_TYPES;
```

Replace with:

```typescript
export class RemunerationSectionComponent implements OnInit {
  private svc = inject(RemunerationService);
  private refData = inject(RefDataService);
  private translate = inject(TranslateService);

  readonly profile = input.required<EmployeeProfile>();

  readonly contractTypes = signal<RefDataItem[]>([]);
```

Find (confirmed current content, the start of `ngOnInit`):

```typescript
  ngOnInit(): void {
    this.svc.get(this.profile().userId).subscribe({
```

Replace with:

```typescript
  ngOnInit(): void {
    this.refData.getContractTypes(this.profile().paysId).subscribe(types => this.contractTypes.set(types));
    this.svc.get(this.profile().userId).subscribe({
```

- [ ] **Step 3: Verify with a real build**

Run: `npm run build -- --configuration development` (from `daf360-rh-frontend` repo root)
Expected: build succeeds with no new TypeScript errors. (This app is federated — if `tsconfig.federation.json` gets auto-regenerated by the build, revert it afterward per this session's established convention; check `git status` for it.)

- [ ] **Step 4: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short`
Expected: only `ref-data.service.ts` and `remuneration-section.component.ts` modified (plus `tsconfig.federation.json` reverted if it appeared). Nothing staged.

---

### Task 5: payroll-frontend — `HrProfileService` + `employee-config.component` (daf360-payroll-frontend)

**Files:**
- Modify: `src/app/core/hr-profile.service.ts`
- Modify: `src/app/modules/employee-config/employee-config.component.ts`
- Modify: `src/app/modules/employee-config/employee-config.component.html`

- [ ] **Step 1: Add `getContractTypes` to `HrProfileService`**

In `src/app/core/hr-profile.service.ts`, find (confirmed current content, the full file):

```typescript
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface EmployeeListItem {
```

Replace with:

```typescript
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface HrContractType {
  code: string;
  labelFr: string;
  labelEn: string;
}

export interface EmployeeListItem {
```

Find (confirmed current content, the end of the file):

```typescript
  searchEmployees(search: string = '', paysId?: number | null, size = 20): Observable<EmployeePage> {
    let params = new HttpParams().set('size', size).set('page', 0);
    if (search.trim()) params = params.set('search', search.trim());
    if (paysId != null) params = params.set('pays', paysId);
    return this.http.get<EmployeePage>(`${this.base}/api/hr/profiles/employees`, { params });
  }
}
```

Replace with:

```typescript
  searchEmployees(search: string = '', paysId?: number | null, size = 20): Observable<EmployeePage> {
    let params = new HttpParams().set('size', size).set('page', 0);
    if (search.trim()) params = params.set('search', search.trim());
    if (paysId != null) params = params.set('pays', paysId);
    return this.http.get<EmployeePage>(`${this.base}/api/hr/profiles/employees`, { params });
  }

  getContractTypes(paysId?: number | null): Observable<HrContractType[]> {
    let params = new HttpParams();
    if (paysId != null) params = params.set('paysId', paysId);
    return this.http.get<HrContractType[]>(`${this.base}/api/hr/ref/contract-types`, { params })
      .pipe(catchError(() => of([])));
  }
}
```

- [ ] **Step 2: Rework `EmployeeConfigComponent`**

In `src/app/modules/employee-config/employee-config.component.ts`, find (confirmed current content):

```typescript
import { PayrollApiService, BenefitCatalogueDto, PaysDto } from '../../core/payroll-api.service';
import { HrProfileService, EmployeePage } from '../../core/hr-profile.service';
import {
  EmployeeConfigService, EmployeePayrollConfigDto, EmployeePayrollBonusDto,
} from './employee-config.service';

// daf360-rh-service's real contract-type domain (EmployeeContract.contractTypeCode /
// ContractTypeConfig.contractTypeCode) — not the narrower 4-value list the payroll
// simulation endpoints' own comments use.
const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'];

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

Replace with:

```typescript
import { PayrollApiService, BenefitCatalogueDto, PaysDto } from '../../core/payroll-api.service';
import { HrProfileService, EmployeePage, HrContractType } from '../../core/hr-profile.service';
import {
  EmployeeConfigService, EmployeePayrollConfigDto, EmployeePayrollBonusDto,
} from './employee-config.service';

const BONUS_CURRENCIES = ['TND', 'EUR', 'USD', 'EGP', 'SAR', 'AED'];
```

Find (confirmed current content):

```typescript
  readonly contractTypes = CONTRACT_TYPES;
  readonly bonusCurrencies = BONUS_CURRENCIES;
```

Replace with:

```typescript
  readonly contractTypes = signal<HrContractType[]>([]);
  readonly bonusCurrencies = BONUS_CURRENCIES;
```

Find (confirmed current content):

```typescript
      next: cfg => {
        this.config.set(cfg);
        this.loading.set(false);
        // Null on a never-configured employee whose country couldn't be auto-resolved — no
        // benefits list to show until the admin picks one via the country select below.
        if (cfg.paysId != null) this.loadBenefitsFor(cfg.paysId);
      },
```

Replace with:

```typescript
      next: cfg => {
        this.config.set(cfg);
        this.loading.set(false);
        // Null on a never-configured employee whose country couldn't be auto-resolved — no
        // benefits list to show until the admin picks one via the country select below.
        if (cfg.paysId != null) {
          this.loadBenefitsFor(cfg.paysId);
          this.loadContractTypesFor(cfg.paysId);
        }
      },
```

Find (confirmed current content):

```typescript
  onPaysChange(paysId: number): void {
    const cfg = this.config();
    if (!cfg) return;
    this.config.set({ ...cfg, paysId, selectedBenefitCodes: [] });
    this.loadBenefitsFor(paysId);
  }
```

Replace with:

```typescript
  onPaysChange(paysId: number): void {
    const cfg = this.config();
    if (!cfg) return;
    this.config.set({ ...cfg, paysId, selectedBenefitCodes: [] });
    this.loadBenefitsFor(paysId);
    this.loadContractTypesFor(paysId);
  }
```

Find (confirmed current content):

```typescript
  private loadBenefitsFor(paysId: number): void {
    this.payrollApi.getActiveParameterSet(paysId).subscribe({
      next: ps => this.benefits.set(ps.benefits ?? []),
      error: () => this.benefits.set([]),
    });
  }
}
```

Replace with:

```typescript
  private loadBenefitsFor(paysId: number): void {
    this.payrollApi.getActiveParameterSet(paysId).subscribe({
      next: ps => this.benefits.set(ps.benefits ?? []),
      error: () => this.benefits.set([]),
    });
  }

  private loadContractTypesFor(paysId: number): void {
    this.hrService.getContractTypes(paysId).subscribe({
      next: types => this.contractTypes.set(types),
      error: () => this.contractTypes.set([]),
    });
  }
}
```

- [ ] **Step 3: Update the template**

In `src/app/modules/employee-config/employee-config.component.html`, find (confirmed current content):

```html
        <select class="rounded-lg border border-gray-300 px-3 py-2"
          [ngModel]="cfg.contractType" (ngModelChange)="onContractTypeChange($event)">
          @for (ct of contractTypes; track ct) {
            <option [value]="ct">{{ ct }}</option>
          }
        </select>
```

Replace with:

```html
        <select class="rounded-lg border border-gray-300 px-3 py-2"
          [ngModel]="cfg.contractType" (ngModelChange)="onContractTypeChange($event)">
          @for (ct of contractTypes(); track ct.code) {
            <option [value]="ct.code">{{ ct.code }}</option>
          }
        </select>
```

- [ ] **Step 4: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short`
Expected: `hr-profile.service.ts`, `employee-config.component.ts`, `employee-config.component.html` modified. Nothing staged. (Build verification for both payroll-frontend tasks happens together in Task 7.)

---

### Task 6: payroll-frontend — `parameter-sets.component` (daf360-payroll-frontend)

**Files:**
- Modify: `src/app/modules/parameter-sets/parameter-sets.component.ts`
- Modify: `src/app/modules/parameter-sets/parameter-sets.component.html`

This is the most involved frontend change: the 4 fixed rubrique checkboxes (`ctCDI`/`ctCDD`/`ctSTAGE`/`ctCIVP`) become a dynamic set keyed by whatever contract types the loaded country actually has.

- [ ] **Step 1: Import `HrProfileService` and inject it**

Find (confirmed current content):

```typescript
import {
  PayrollApiService,
  ParameterSetDto,
  SocialChargeRateDto,
  SavePayrollRubriqueRequest,
  PayrollRubriqueDto,
} from '../../core/payroll-api.service';
import { PaysSelectComponent } from '../../shared/pays-select/pays-select.component';
```

Replace with:

```typescript
import {
  PayrollApiService,
  ParameterSetDto,
  SocialChargeRateDto,
  SavePayrollRubriqueRequest,
  PayrollRubriqueDto,
} from '../../core/payroll-api.service';
import { HrProfileService, HrContractType } from '../../core/hr-profile.service';
import { PaysSelectComponent } from '../../shared/pays-select/pays-select.component';
```

Find (confirmed current content):

```typescript
export class ParameterSetsComponent {
  private readonly api = inject(PayrollApiService);
  private readonly fb  = inject(FormBuilder);
```

Replace with:

```typescript
export class ParameterSetsComponent {
  private readonly api = inject(PayrollApiService);
  private readonly hr  = inject(HrProfileService);
  private readonly fb  = inject(FormBuilder);
```

- [ ] **Step 2: Replace the hardcoded array with a fetched signal**

Find (confirmed current content):

```typescript
  readonly contractTypes   = ['CDI', 'CDD', 'STAGE', 'CIVP'];
```

Replace with:

```typescript
  readonly contractTypes   = signal<HrContractType[]>([]);
```

- [ ] **Step 3: Fetch contract types when loading a country**

Find (confirmed current content):

```typescript
  load(): void {
    const paysId = this.filterForm.get('paysId')?.value;
    if (!paysId) return;
    this.loading.set(true);
    this.error.set(null);
    this.api.listParameterSets(paysId).subscribe({
```

Replace with:

```typescript
  load(): void {
    const paysId = this.filterForm.get('paysId')?.value;
    if (!paysId) return;
    this.loading.set(true);
    this.error.set(null);
    this.hr.getContractTypes(paysId).subscribe(types => this.contractTypes.set(types));
    this.api.listParameterSets(paysId).subscribe({
```

- [ ] **Step 4: Make the rubrique form group's contract-type controls dynamic**

Find (confirmed current content):

```typescript
  private makeRubriqueGroup(r: Partial<PayrollRubriqueDto>): FormGroup {
    return this.fb.group({
      code:                     [r.code     ?? '',           Validators.required],
      labelFr:                  [r.labelFr  ?? '',           Validators.required],
      labelEn:                  [r.labelEn  ?? ''],
      nature:                   [r.nature   ?? 'AVANTAGE'],
      calcMode:                 [r.calcMode ?? 'FIXE_MENSUEL'],
      amount:                   [r.amount   ?? null],
      ratePercent:              [r.rate != null ? +(r.rate * 100).toFixed(4) : null],
      capAmount:                [r.capAmount ?? null],
      formulaExpression:        [r.formulaExpression ?? ''],
      employerSharePct:         [r.employerSharePct  ?? 0],
      employeeSharePct:         [r.employeeSharePct  ?? 0],
      isSubjectToSocialCharges: [r.isSubjectToSocialCharges ?? false],
      isSubjectToIrpp:          [r.isSubjectToIrpp   ?? true],
      ctAll:                    [!r.contractTypes],
      ctCDI:                    [r.contractTypes?.includes('CDI')   ?? false],
      ctCDD:                    [r.contractTypes?.includes('CDD')   ?? false],
      ctSTAGE:                  [r.contractTypes?.includes('STAGE') ?? false],
      ctCIVP:                   [r.contractTypes?.includes('CIVP')  ?? false],
      isActive:                 [r.isActive  ?? true],
    });
  }
```

Replace with:

```typescript
  private makeRubriqueGroup(r: Partial<PayrollRubriqueDto>): FormGroup {
    const selectedCodes = new Set(
      (r.contractTypes ?? '').split(',').map(s => s.trim()).filter(Boolean)
    );
    // Relies on contractTypes() already being populated — true in practice, since this only
    // runs from openRubriquesEditor()/addEditRubrique(), both reachable only after load() has
    // fired (and its contract-types fetch, a small reference-data call, resolves far faster
    // than a human can click into the editor). Worst case if it hasn't: an empty ctByCode
    // group for that card, self-corrects on the next load().
    const ctByCodeControls: Record<string, [boolean]> = {};
    this.contractTypes().forEach(ct => { ctByCodeControls[ct.code] = [selectedCodes.has(ct.code)]; });

    return this.fb.group({
      code:                     [r.code     ?? '',           Validators.required],
      labelFr:                  [r.labelFr  ?? '',           Validators.required],
      labelEn:                  [r.labelEn  ?? ''],
      nature:                   [r.nature   ?? 'AVANTAGE'],
      calcMode:                 [r.calcMode ?? 'FIXE_MENSUEL'],
      amount:                   [r.amount   ?? null],
      ratePercent:              [r.rate != null ? +(r.rate * 100).toFixed(4) : null],
      capAmount:                [r.capAmount ?? null],
      formulaExpression:        [r.formulaExpression ?? ''],
      employerSharePct:         [r.employerSharePct  ?? 0],
      employeeSharePct:         [r.employeeSharePct  ?? 0],
      isSubjectToSocialCharges: [r.isSubjectToSocialCharges ?? false],
      isSubjectToIrpp:          [r.isSubjectToIrpp   ?? true],
      ctAll:                    [!r.contractTypes],
      ctByCode:                 this.fb.group(ctByCodeControls),
      isActive:                 [r.isActive  ?? true],
    });
  }
```

- [ ] **Step 5: Update the save mapping**

Find (confirmed current content, inside `saveRubriques`):

```typescript
      contractTypes:            r.ctAll ? null :
        (['CDI', 'CDD', 'STAGE', 'CIVP'] as const)
          .filter((t: string) => r['ct' + t] === true)
          .join(',') || null,
```

Replace with:

```typescript
      contractTypes:            r.ctAll ? null :
        Object.entries(r.ctByCode ?? {})
          .filter(([, checked]) => checked === true)
          .map(([code]) => code)
          .join(',') || null,
```

- [ ] **Step 6: Update the template — social-charge-rate select**

In `src/app/modules/parameter-sets/parameter-sets.component.html`, find (confirmed current content):

```html
                              <select class="ri" formControlName="contractType">
                                @for (ct of contractTypes; track ct) { <option [value]="ct">{{ ct }}</option> }
                              </select>
```

Replace with:

```html
                              <select class="ri" formControlName="contractType">
                                @for (ct of contractTypes(); track ct.code) { <option [value]="ct.code">{{ ct.code }}</option> }
                              </select>
```

- [ ] **Step 7: Update the template — rubrique contract-type checkboxes**

Find (confirmed current content):

```html
                                  <label class="rg-checkbox-item">
                                    <input type="checkbox" formControlName="ctAll" />
                                    <span>
                                      <strong>Tous les types de contrat</strong>
                                      <small>CDI, CDD, Stage, CIVP — aucune restriction</small>
                                    </span>
                                  </label>
                                  @if (!editRubriqueGroup(i).get('ctAll')!.value) {
                                    <div class="rg-contract-types">
                                      <label class="rg-tag-check"><input type="checkbox" formControlName="ctCDI" /> CDI</label>
                                      <label class="rg-tag-check"><input type="checkbox" formControlName="ctCDD" /> CDD</label>
                                      <label class="rg-tag-check"><input type="checkbox" formControlName="ctSTAGE" /> Stage</label>
                                      <label class="rg-tag-check"><input type="checkbox" formControlName="ctCIVP" /> CIVP</label>
                                    </div>
                                  }
```

Replace with:

```html
                                  <label class="rg-checkbox-item">
                                    <input type="checkbox" formControlName="ctAll" />
                                    <span>
                                      <strong>Tous les types de contrat</strong>
                                      <small>Aucune restriction — s'applique à tous les types configurés pour ce pays</small>
                                    </span>
                                  </label>
                                  @if (!editRubriqueGroup(i).get('ctAll')!.value) {
                                    <div class="rg-contract-types" formGroupName="ctByCode">
                                      @for (ct of contractTypes(); track ct.code) {
                                        <label class="rg-tag-check"><input type="checkbox" [formControlName]="ct.code" /> {{ ct.code }}</label>
                                      }
                                    </div>
                                  }
```

- [ ] **Step 8: Confirm scope, DO NOT STAGE OR COMMIT**

Run: `git status --short`
Expected: `parameter-sets.component.ts` and `parameter-sets.component.html` modified. Nothing staged.

---

### Task 7: Frontend build verification (both frontend repos)

Pure verification task, no code changes. Single-pass verification agent.

- [ ] **Step 1: Build `daf360-payroll-frontend`**

Run: `npm run build -- --configuration development` (from `daf360-payroll-frontend` repo root)
Expected: build succeeds with no new TypeScript errors from Tasks 5–6's changes. Revert `tsconfig.federation.json` afterward if the build auto-regenerated it (check `git status`).

- [ ] **Step 2: Build `daf360-rh-frontend`**

Run: `npm run build -- --configuration development` (from `daf360-rh-frontend` repo root)
Expected: build succeeds (this repeats Task 4's own build check — safe to skip only if nothing has touched this repo since Task 4 ran; if unsure, re-run it).

- [ ] **Step 3: Confirm scope in both repos, DO NOT STAGE OR COMMIT**

Run `git status --short` in both `daf360-payroll-frontend` and `daf360-rh-frontend`.
Expected: only the files already listed in Tasks 4–6, nothing staged.

Report DONE with the build output confirmation for both repos, or DONE_WITH_CONCERNS with the exact TypeScript error(s) if either build fails.

---

### Task 8: Final holistic review (all three repos)

Pure verification task, no code changes. Single-pass verification agent — this is the final gate before reporting back to the user.

- [ ] **Step 1: Confirm no orphaned hardcoded contract-type lists remain**

Run, from each repo root:
- `grep -rn "CDI.*CDD.*STAGE.*CIVP\|CIVP.*STAGE.*FREELANCE" src/` in `daf360-payroll-frontend` and `daf360-rh-frontend`
Expected: no matches — confirms the old hardcoded arrays in `parameter-sets.component.ts`, `employee-config.component.ts`, and `remuneration-section.component.ts` are gone. (`profile-labels.ts`'s `CONTRACT_CODES` `Set` in `daf360-rh-frontend` is explicitly out of scope per the design doc's Non-Goals — it's a label-rendering allowlist, not a selectable list — confirm it is untouched, not removed.)

- [ ] **Step 2: Confirm no orphaned `ct{CODE}` form-control references remain**

Run: `grep -rn "ctCDI\|ctCDD\|ctSTAGE\|ctCIVP" src/` in `daf360-payroll-frontend`
Expected: no matches in `parameter-sets.component.ts` or `.html` (Task 6 replaced all four with the dynamic `ctByCode` group).

- [ ] **Step 3: Re-verify the real dev-DB seed data**

Re-run the same query from Task 3, Step 2, against the live dev DB, to confirm the data is still exactly as seeded (nothing since Task 3 has dropped or altered the table): `179 → 11` rows, `53 → 10` rows, no `CIVP` for pays_id 53.

- [ ] **Step 4: Full backend test suite one more time**

Run: `./mvnw.cmd clean test` (from `daf360-rh-service` repo root)
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Confirm nothing was staged or committed in any of the three repos**

Run, in `daf360-rh-service`, `daf360-rh-frontend`, `daf360-payroll-frontend`:
- `git log -3 --oneline` — expected: no new commits beyond the one design-spec commit already made in `daf360-rh-service` before this plan started.
- `git status --short` — expected: every line is plain `M` or `??`, never staged (`A`, `M ` with a staged marker, etc.).

Report DONE with: final confirmation of all five checks above, and a summary of every file touched across the three repos, ready for the user's review before any commit/push.
