# Employee Payroll Configuration — Design

## 1. Goal

Give each employee their own payroll configuration — country, contract type, selected
benefits-in-kind, and current net salary — editable from two places: a new **Rémunération**
tab on the RH employee profile page, and a new screen in the Payroll app. The current net
salary becomes canonical in `daf360-payroll-service` going forward; RH's existing
`salaireNetRh` field stops being editable (superseded), while `salaireNetCandidat` (a
candidate's original ask, a historical negotiation figure) is untouched.

## 2. What actually exists today (confirmed against real code, not the functional doc)

- **No per-employee payroll configuration exists anywhere.** `ParameterSet`
  (`daf360-payroll-service`, table `parameter_sets`) and the parallel `PayrollParamSet`
  (table `payroll_parameter_sets`) are both scoped to `paysId` (country/entity) + version —
  never to an individual employee. There is no precedent to extend; this is new.
- **Salary is not persisted anywhere in `daf360-payroll-service` today.** It lives in
  `daf360-rh-service`: `EmployeeProfile.salaireNetCandidat`/`salaireNetRh`
  (`NUMERIC(10,3)`, both net) and `Contract.gross_salary` (per contract period). Inside
  payroll-service, salary only ever appears as a transient simulation input
  (`SimulationRequest.inputNet`/`inputGross`) or as an immutable log of past simulation runs
  (`SimulationResult.inputNet`) — never a live, updatable, per-employee record.
- **`salaireNetRh`'s real role today**: "Salaire net proposé par RH" — the net figure RH
  proposed at hiring/offer time, editable in `employment-section.component.ts`'s
  "Prétentions & rémunération" card (`daf360-rh-frontend`). This is exactly the field being
  superseded by the new canonical payroll-service value.
- **The simulation endpoint** (`POST /api/payroll/simulations/individual`,
  `IndividualSimulationService.simulate()`) auto-resolves the active `ParameterSet` by
  `paysId` alone (`paramSetService.loadActiveEntity(paysId)`) and takes `contractType`,
  `selectedBenefitCodes` etc. as **request-body overrides** — there is no `parameterSetId`
  field. An optional `profileUserId` is used only as convenience hydration (fetches
  grade/discipline/contractType from rh-service via `HrEmployeeClient` if present; never
  required, never fatal if rh-service is unreachable).
- **Precedent for "assign X to a specific employee"** exists in `daf360-rh-service`'s
  Working-Time-Regime module, not in payroll-service: `POST /api/hr/profiles/{profileId}/regime`
  takes a `RegimeAssignmentDto` (`regimeId`, `startDate`, `endDate`, `reason`) and records an
  append-only `RegimeAssignmentHistory` row alongside updating the live assignment. This shape
  (mutable "current" row + append-only history table with a reason) is what the new employee
  payroll config follows.
- **Role→permission mapping is data-driven**, via a `RolePermission` entity/table, not
  hardcoded in Java, and not seeded via migration for any existing `PAYROLL_*` permission —
  every prior one was granted to roles by an admin through the existing admin UI after the
  code shipped. This design follows the same convention: new permission codes are defined in
  code; granting them to HR_MANAGER/FINANCE_MANAGER is a post-deploy admin action, not part of
  this implementation.
- **`daf360-payroll-service`'s migration folder was found empty at HEAD** (all 22 files
  deleted in the tip commit, `aa55034`, alongside an unrelated and correctly-still-deleted old
  seed script). All 22 have already been restored from git history and verified byte-identical
  against the pre-deletion commit, as a prerequisite fixed before this feature's own migration
  is added on top (`V23`).
- **RH profile page architecture**: `profile-detail.component.ts` has two different tab
  patterns. `emploi`/`contact`/`bancaire` share ONE global `editForm: ProfileUpdateDto` signal,
  saved together via a single `PATCH /api/hr/profiles/{id}`. `historique`/`materiel` are
  self-contained instead — their own local state, their own dedicated save actions (e.g. the
  "New contract" modal), entirely independent of `editForm`. The new **Rémunération** tab
  follows the *second* pattern: its data belongs to a different backend (payroll-service)
  entirely, so it cannot share the RH `editForm`/`ProfileUpdateDto` save cycle — matching the
  precedent this same file already has for exactly this situation.
- **`daf360-rh-frontend`'s existing payroll integration** (`PayrollSimulationService`, used by
  the candidate-costing flow) calls `daf360-payroll-service` **directly** from the Angular app,
  via `environment.payrollApiUrl`, bypassing rh-service entirely. The new Rémunération tab's
  service follows this exact same precedent, for the same reason: consistency, and because
  rh-service has no reason to proxy a resource it doesn't own.
- **`daf360-payroll-frontend` has no existing single-employee page** — every screen is either
  entity-wide administration (`parameter-sets`) or a one-shot calculation with an employee
  picked only as transient input (`simulator`, `engine-run`, both via the shared
  `EmployeeSelectComponent`). Several of its modules (`cohort`, `calibration`, `parameter-sets`,
  `budget`, `engine-run`, `engine-results`) are built but their routes are currently commented
  out in `app.routes.ts` — the app is mid-rollout. The new module's route is added active (not
  commented out), since this is a requested, ready-to-use feature, not a parked one.

## 3. New backend entity — `EmployeePayrollConfig` (`daf360-payroll-service`)

One row per employee (`profileUserId`, the same cross-service identifier
`HrEmployeeClient`/`UsersRef` already use), mutable "current state", plus an append-only
history table for audit — mirroring the Working-Time-Regime precedent above:

```
employee_payroll_config
  id                  BIGINT PK
  profile_user_id     BIGINT NOT NULL UNIQUE   -- one row per employee
  pays_id             BIGINT NOT NULL          -- overridable; normally inherited from the
                                                -- employee's own profile at first setup
  contract_type       VARCHAR NOT NULL         -- same domain as SimulationRequest.contractType
  selected_benefit_codes  VARCHAR (JSON array, mirrors SimulationRequest.selectedBenefitCodes)
  current_net_salary  NUMERIC(12,3) NULL       -- nullable: starts empty, populated per employee
                                                -- as each one is configured — see §6, no bulk
                                                -- backfill from RH's existing salaireNetRh
  updated_by          BIGINT NOT NULL
  updated_at          TIMESTAMP NOT NULL
  created_at          TIMESTAMP NOT NULL

employee_payroll_config_history
  id                  BIGINT PK
  profile_user_id     BIGINT NOT NULL
  pays_id             BIGINT NOT NULL          -- value AFTER this change
  contract_type       VARCHAR NOT NULL         -- value AFTER this change
  selected_benefit_codes  VARCHAR
  current_net_salary  NUMERIC(12,3) NULL
  reason              VARCHAR NOT NULL         -- required, matches RegimeAssignmentDto's own
                                                -- required `reason`, since this changes real pay
  changed_by          BIGINT NOT NULL
  changed_at          TIMESTAMP NOT NULL
```

Package: `com.daf360.payroll.modules.employeeconfig.{controller,dto,entity,repository,service}`,
matching the existing per-feature module convention.

## 4. New backend endpoints (`daf360-payroll-service`)

Base path `/api/payroll/employee-configs`, matching the `<module-plural-kebab>` convention:

- `GET /{profileUserId}` — current config, or a "not configured yet" 404/empty response the
  frontend renders as an empty form pre-filled with the employee's own `pays_id`/`contractType`
  from `HrEmployeeClient` as sensible defaults (mirroring how the simulation endpoint already
  uses that same client for the same purpose).
- `PUT /{profileUserId}` — upsert: creates the row on first save, updates it thereafter; always
  appends one `employee_payroll_config_history` row. Request DTO requires `reason` (matching
  `RegimeAssignmentDto`'s pattern — this changes real pay, always justified).
- `GET /{profileUserId}/history` — the audit trail (no dedicated UI screen is built for this in
  this iteration — see §7 — but the endpoint exists from day one since the history table is
  pointless without a way to read it back, and it costs nothing extra to expose alongside the
  entity itself).

New permission codes (in `daf360-payroll-service`'s `PermissionCatalog`, mirrored in
`daf360-rh-service`'s per that file's own documented sync requirement):
`PAYROLL_VIEW_EMPLOYEE_CONFIG`, `PAYROLL_MANAGE_EMPLOYEE_CONFIG`. Granting these to
HR_MANAGER/FINANCE_MANAGER is a post-deploy admin action (see §2's role-mapping note) — not
part of this implementation, and called out explicitly in the final report.

## 5. Frontend — two editors, one backend

**`daf360-rh-frontend`**: new `remuneration` tab on `profile-detail.component.ts`, added to the
`TabId` union and the `tabs` computed, rendered via a new
`detail-sections/remuneration-section.component.ts`. It is **not** added to `TAB_FIELDS` (see
§2's architecture note) — it manages its own local form state and calls a new
`RemunerationService` (calling `daf360-payroll-service` directly via
`environment.payrollApiUrl`, mirroring `PayrollSimulationService`) with its own save action and
its own `reason` prompt, independent of the page's global edit/save cycle.

**`daf360-payroll-frontend`**: new `employee-config` module — an employee search (reusing the
existing shared `EmployeeSelectComponent`, the same component `simulator`/`engine-run` already
use to pick an employee) followed by the same config form (country/contract-type/benefits/
current net salary), wired to the same `GET`/`PUT /api/payroll/employee-configs/{id}` endpoints.
Route added active under `app.routes.ts`'s existing lazy-route list.

**`daf360-rh-frontend`**'s `employment-section.component.ts`: the edit-mode
`salaireNetRh` `daf-form-field` is removed. In its place, edit mode shows the same read-only
`rh-profile-field` display it already uses in view mode, plus a short note pointing to the new
Rémunération tab. `salaireNetRh` is removed from `TAB_FIELDS.emploi` (nothing will ever patch it
from this tab again). `salaireNetCandidat` is entirely untouched — still editable, since it's a
candidate-side negotiation record, not a current-salary field.

## 6. What does NOT change / explicitly out of scope

- **No bulk migration of existing `salaireNetRh` values into the new payroll-service field.**
  The new field starts empty (`NULL`) for every employee and gets populated one employee at a
  time, from either app, going forward. `salaireNetRh`'s historical accuracy for already-tenured
  employees is uncertain (it reads as an offer-time figure, not something necessarily kept
  current) — copying it wholesale as if it were today's real salary would be a real, consequential
  data-quality risk this design does not take unilaterally. If a backfill is wanted later, it's
  a separate, explicit decision.
- **No changes to `Contract.gross_salary`** — a different, historical, per-contract-period
  record, unrelated to "current salary."
- **No dedicated history/audit UI screen** in either frontend this iteration — the backend
  history table and its read endpoint exist (see §4), a UI to browse it is future work.
- **No changes to the simulation endpoint's own resolution logic** — it still takes
  `contractType`/`selectedBenefitCodes`/etc. as request overrides; this design does not wire the
  new per-employee config as an automatic input to simulations (e.g., "pre-fill a simulation from
  this employee's saved config" is a natural future enhancement, explicitly not built here).
- **Role→permission grants** for the two new codes are a post-deploy admin action, not part of
  this implementation (see §2, §4).

## 7. Out of scope (explicitly deferred, not forgotten)

- Bulk backfill of historical salary data (§6).
- A UI for browsing `employee_payroll_config_history`.
- Auto-feeding a saved employee config into the simulation/engine-run flows.
- Restoring payroll-frontend's other currently-commented-out routes (`cohort`, `calibration`,
  `parameter-sets`, `budget`, `engine-run`, `engine-results`) — unrelated to this feature, their
  parked state is pre-existing and untouched.
