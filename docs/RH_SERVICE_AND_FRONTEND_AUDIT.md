# DAF360-RH — Architecture Audit (Backend + Frontend)

**Date:** 2026-10-25
**Scope:** `daf360-rh-service` (Spring Boot 4.0.5 / Java 17) and `daf360-rh-frontend` (Angular 21.2, native-federation micro-frontend remote `rh`, mounts at `/rh`).
**Purpose:** Ground-truth reference for drafting feature prompts (Claude Desktop or otherwise) against the RH module. Part of the platform-wide documentation set — see `PLATFORM_MASTER_INDEX.md` at the projects root for the full index.

**Caveat that governs everything below**: `daf360-rh-service` has **no Flyway wired in** (no Flyway dependency on the classpath at all; schema evolves via `ddl-auto: update` for ordinary changes). Migrations are SQL scripts applied **by hand** against the live DB. Only the 3 most recent are checked into `src/main/resources/db/seed/` (V101–V103); the full historical set (V2–V119+) lives in the separate `daf360-sql/db-rh/seed/` repo. This means code can legitimately reference tables/columns that don't exist yet on a given environment's live DB — several such cases are flagged explicitly below.

---

## 1. Module structure (`src/main/java/com/daf360/rh/`)

| Package | Business domain |
|---|---|
| `audit` | `AuditableEntity` base class — append-only audit columns. |
| `common` | `PermissionCatalog`, `DocumentVariableCatalog`, `OffboardingStagePermissions`, `PageResponse`, `UserScope`, `GenderNormalizer`. |
| `config` | `SecurityConfig`, `WebConfig`, `AppProperties`, `RabbitMQConfig`, `JacksonConfig`, `JpaAuditingConfig`, `DotenvEnvironmentPostProcessor`. |
| `controller` | 44 REST controllers (§13). |
| `domain` | 80 JPA entities + enums. |
| `dto` | 205 files, organised by sub-feature. |
| `exception` | `AppException`, `ErrorCode`, `BusinessRuleException`, global handler. |
| `lifecycle` | Employee Lifecycle Engine: `EmployeeLifecycleService`, `LifecycleStateMachine`, `OffboardingAlertJob`, `LifecycleAlertJob`, `PresenceStatusJob`, `ContractTypeBridge`. |
| `lists` | Generic configurable-list system (§4). |
| `mapper` | 5 mappers. |
| `notification` | Notification-routing engine (§8). |
| `pipeline` | Recruitment kanban support. |
| `repository` | 58 Spring Data JPA repositories. |
| `scheduler` | `HrScheduler` (legacy contract-expiry check), `SlaEscalationScheduler`. |
| `security` | `JwtAuthFilter`, `JwtService`, `HrPermissionEvaluator`, `TenantContext`/`TenantService`, `PaysScopeContext` (§9, §12). |
| `service` | 78 files + `service/calendar`, `service/document`, `service/payslip`, `service/pdf`, `service/photo`, `service/sharepoint`. |
| `validator` | `EmployeeIdValidator`, `FixedTermContractValidator`. |

Note: the repo root contains `.claude/worktrees/agent-*` leftover duplicate checkouts from prior agent sessions — excluded from analysis, worth cleaning up.

---

## 2. Core domain model: employee lifecycle

### The four "contract" concepts — do not confuse them

1. **`contrats` → `Contract.java`** — old, simple contract record. Used **only** by `HrScheduler.checkExpiringContracts()` (daily cron, just logs a count). **No controller reads or writes it.** Pre-V32 model, superseded, kept alive by one forgotten job.
2. **`employee_contracts` → `EmployeeContract.java`** — the **current, rich** Employee Lifecycle Engine (V32) model: per-contract-type fields (CIVP/STAGE/FREELANCE/DETACHEMENT), `current_status_code`, `notice_period_days` (frozen préavis, V69), CDD renewal chain (`cdd_contrat_parent_id`), avenant chain (`avenant_parent_id`). This is what `EmployeeLifecycleController`/`EmployeeLifecycleService` operate on.
3. **`historique_contrat_collaborateur` → `HistoriqueContrat.java`** — a **separate, independent** French-named contract-history ledger, driven by its own `type_contrat` reference table, exposed via `ContractHistoryController` (`/api/hr/profiles/{id}/contrats`, `/api/hr/ref/type-contrat`).
4. **`contract_types` → `ContractType.java`** — the dedicated reference table behind `/api/hr/ref/contract-types` (§4). Used by neither #2 nor #3 directly.

No code path reconciles these four models with each other — four independently-evolved subsystems (D3-9x change codes throughout `EmployeeLifecycleService.java`). **A change to "contracts" must specify which of the four models it means.**

### Lifecycle state machine

`EmployeeProfile.lifecycleStatus` (`domain/enums/LifecycleStatus.java`):
```
PRE_ONBOARDING → ACTIVE
ACTIVE → ON_LEAVE | ON_MISSION | OFFBOARDING
ON_LEAVE → ACTIVE | OFFBOARDING
ON_MISSION → ACTIVE | OFFBOARDING
OFFBOARDING → TERMINATED | ACTIVE   (cancellation)
TERMINATED → ARCHIVED | OFFBOARDING (reopen)
ARCHIVED → terminal; PII pseudonymised on entry
```
`ON_MISSION`/`ON_LEAVE` are driven automatically by `lifecycle/PresenceStatusJob.java` (daily, checks approved `Mission` records and leave sources) — previously unreachable until this job was added.

`EmployeeContract.currentStatusCode` has its **own separate** per-contract-type state machine (`LifecycleStateMachine.java`): CDI/CDD/CIVP/STAGE/FREELANCE/DETACHEMENT each define their own string-status graph (e.g. CDD: `RECRUTEMENT→PERIODE_ESSAI→ACTIF→RENOUVELLEMENT_CDD|CONVERSION_CDI|FIN_CONTRAT→INACTIF`). `EmployeeProfile.lifecycleStatusCode` mirrors whichever contract status is current.

### Onboarding
- `candidates.status = HIRED` → `POST /api/hr/candidates/{id}/hire` creates the `employee_profiles` row.
- `OnboardingController` (`/api/hr/onboarding`): `/kpi`, `/pending`, `/{candidateId}/form`, `/{candidateId}/draft`, `/{candidateId}/contract-document`, `/{candidateId}/complete`.
- **`onboarding_drafts` has no JPA entity** — accessed exclusively via raw `JdbcTemplate` SQL in `OnboardingService.java` (SELECT/UPDATE/INSERT/DELETE, storing the wizard state as a JSON blob).
- `OnboardingService.getPendingList()` filters `candidates.status IN (EMAIL_RECEIVED, HR_IN_PROGRESS)`.
- Completion creates the `employee_contracts` row (via `EmployeeLifecycleService.createContractFromBridge`), writes **one** `workflow_instances` row (`event_type='ONBOARDING'`, raw SQL INSERT only — see §8), allocates the payroll matricule, resolves FKs against grade/discipline/nog_level/department/nationality/bank.

### Offboarding — the full 7-stage wizard
`OffboardingController` (base `/api/hr/offboarding`, ~34 endpoints) backed by `OffboardingWorkflowService.java` (~2,460 lines):

1. **Declaration** (RH) — `startOffboarding()`, `updateDeclaration()`. Transitions `lifecycleStatus → OFFBOARDING`.
2. **Validation manager/RH** — `validateAsManager()`, `validateAsHr()` (V66's `offboarding_validators` decides, per country, who the "RH validation" role is).
3. **Passation/handover** — `updateHandover()`; checklist group `HANDOVER`.
4. **Informatique & Matériel** — `updateItSecurity()`, asset-return endpoints; checklist group `ACCESS`; task codes `ASSET_RETURN_IT`, `ASSET_RETURN_BADGE`, `IT_ACCESS_REVOKE`.
5. **Kit RH** — exit interview, document kit generation; checklist group `KIT` (`WORK_CERTIFICATE`, `END_OF_CONTRACT`, `SETTLEMENT_RECEIPT`).
6. **Paie & STC** — `updateSettlement()`, `suggestSettlement()`, settlement-line CRUD (`offboarding_settlement_lines`, §10).
7. **Archive** — `validateWorkflow()` → `archiveWorkflow()`.

Supporting: `cancelWorkflow()`, `reopenWorkflow()`, full audit trail, Décharge PDF (`generateDischarge()`).

The six `offboarding_*` tables map 1:1 to entities: `OffboardingWorkflowInstance`, `OffboardingTask` (seeded from `OffboardingTaskCatalog`, admin-editable), `OffboardingChecklistItem` (HANDOVER/ACCESS/KIT groups), `OffboardingAssetReturn`, `OffboardingSettlementLine`, `OffboardingValidator`.

`EmployeeLifecycleAlert`/`EmployeeLifecycleTransition`: generic alert/audit tables tied to `EmployeeContract` (not offboarding-specific) — `planContractAlerts()` seeds `CONTRACT_EXPIRY_30D`; `logTransition()` writes every contract-status change append-only.

---

## 3. Recruitment / hiring pipeline

`CandidateStatus`: `PENDING→ACCEPTED→OFFER_SENT→...→HIRED`, plus `REJECTED`/`EMAIL_RECEIVED`/`HR_IN_PROGRESS`/`IT_IN_PROGRESS`/`ARCHIVED`.

1. **Recruitment demand**: `RecruitmentDemandController` (`/api/hr/recruitment-demands`) — manager raises a need, HR reviews/approves/cancels.
2. **Candidate intake**: `CandidateController` (`/api/hr/candidates`) — CRUD, CV upload/download, dashboard stats.
3. **Pipeline/kanban**: `PipelineController` (`/api/hr/pipeline`) — `/stats`, `/kanban`, `/candidates`, `PUT /candidates/{id}/stage`, `/activity`, `/objectives`.
4. **Interviews**: `CandidateInterviewController` + `candidate_interview_interviewers` (multi-interviewer). `InterviewTypeController` manages the admin-editable interview-type catalog.
5. **Cost approval / budget gate**: `CandidateCostApprovalController` (`/api/hr/cost-approvals`) — §10.
6. **Offer/negotiation**: `OfferService`/`JobOffer`. **V98 changed `job_offers` to hold one row per negotiation round** — accept/reject/counter cycles are rounds, and the frozen préavis is resolved from "the round the candidate ACCEPTED," not the latest round.
7. **Hire**: `POST /api/hr/candidates/{id}/hire` → onboarding (§2).

---

## 4. Reference data: generic configurable lists vs. dedicated tables

**Generic system** — `lists/ConfigurableListController.java`, base `/api/hr/lists`: `GET /types`, `GET /{listTypeCode}?pays=`, `GET /{listTypeId}/values/all` (admin), `POST /values`, `PATCH /values/{id}`, `DELETE /values/{id}`, `PATCH /{listTypeId}/reorder`. Backs `configurable_list_types`/`configurable_list_values`.

**Dedicated tables**, all under `ReferenceDataController.java`, base `/api/hr/ref`:

| Table | Endpoint(s) | Notes |
|---|---|---|
| `grades` | `GET/POST /grades`, `PUT /grades/{id}/notice-period`, `DELETE` | |
| `disciplines` | `GET/POST /disciplines`, `DELETE` | |
| `nog_levels` | `GET/POST /nog-levels`, `DELETE` | |
| `departments` | `GET/POST /departments`, `DELETE` | |
| `banks` | `GET/POST /banks`, `DELETE` | |
| `nationalities` | `GET/POST /nationalities`, `DELETE` | global, not per-country |
| `it_asset_types` | `GET/POST /it-asset-types`, `DELETE` | read-only, seeded via V23 |
| `contract_types` | `GET /contract-types` only | per-country; read-only, no admin UI yet |

All reads require `isAuthenticated()`; all writes require `ADMIN_LISTS`. Country scoping via a private `readableP()` helper resolving the caller's V74 pays-scope (OWN/LIST/ALL) rather than trusting the client-supplied `paysId` (fixes a prior data-leak bug).

**IMPORTANT, confirmed root cause of a real bug fixed this session**: two codes (`GRADE`, `DISCIPLINE`) were **never defined in the generic configurable-list system at all** (confirmed empty on both localhost and 192.168.1.233). Any frontend code that mistakenly calls the generic `/api/hr/lists/GRADE` or `/DISCIPLINE` endpoint instead of the dedicated `/api/hr/ref/grades`/`/disciplines` gets an always-empty list, silently, country-independent. This caused the Payroll Simulator's Grade/Discipline dropdowns to always be empty — fixed in `daf360-payroll-frontend/src/app/core/hr-ref-api.service.ts` (2026-10-23). **Any new feature needing grade/discipline data must call the dedicated `/api/hr/ref/{grades,disciplines}` endpoints, never the generic list endpoints.**

Same controller also owns: pays timezones (`/pays`, `/pays/{id}/timezone`), pays weekend/scheduling (`/pays/scheduling`, `/pays/{id}/scheduling` → `pays_weekends`), the caller's own holidays (`/my/holidays`), and a `/api/hr/ref/debug` diagnostic endpoint that queries `flyway_schema_history` directly (the only place that table is touched — confirms Flyway bookkeeping exists in the DB from an external/manual process, not from this app's own boot).

---

## 5. Document management & SharePoint filing

- **`document_templates`** → `DocumentTemplate.java` — admin CRUD + render via `DocumentTemplateController` (`/api/hr/admin/document-templates`): list/get/create/update/toggle-active/delete, `POST /{id}/render` (fill template for one employee → PDF), `POST /preview-raw`. `DocumentTemplateService` resolves `{{variable}}` tokens via `DocumentVariableCatalog` and company "DG parameters" pulled from `parameter_sets` (§7).
- **`document_template_versions`**: **zero code references anywhere** — a version-history feature that was planned (table exists) but never implemented.
- **`generated_documents`** → `GeneratedDocument.java` — FK'd to `employee_requests`, carries a `verification_code`.
- **`employee_documents`** → `EmployeeDocument.java` — the real per-employee document locker: upload/verify/soft-delete (V77 adds `is_deleted`/`deleted_by`), `storage_provider` (`LOCAL`|`SHAREPOINT`; this specific table's own upload path is local-disk only). Controller: `EmployeeDocumentController` (`/api/hr/profiles/{id}/documents`).
- **`documents_hr`**: **no entity/repository/controller/raw-SQL reference found anywhere** — only a stale comment in `EmployeeDocument.java` referencing a non-existent `Document.java`. Near-certainly dead, superseded by `employee_documents`.
- **`document_types`** (used extensively, **not confirmed present on every environment**): `DocumentTypeService` manages a per-country document-type vocabulary (replacing a hardcoded Java `Set`), with an explicit fallback to that Java set "on a server that has not been migrated" (V87). **Verify this table exists before assuming it everywhere.**

**SharePoint filing** (independent subsystem, `service/sharepoint/`):
- `sharepoint_locations` → `SharePointLocationService` — per-(country, document-kind) path templates (V84), 60s-cached, with a documented fallback to a legacy `pays.photo_sharepoint_location` column for photos.
- `employee_sharepoint_folders` → `EmployeeSharePointFolderStore` — a cache, deliberately sparse ("no row = not looked up yet"), caches negative results too (avoids Graph API throttling).
- `GraphSharePointService.java` — the actual MS Graph upload/download client.
- `SharePointDeltaService.java` → references a `sharepoint_delta_state` table (**not confirmed present on every environment**).
- `SharePointAdminController` (`/api/hr/sharepoint`) — admin screen (`ADMIN_SHAREPOINT`).

Generated-document/payslip filing (`PdfDocumentService`, `PayslipBatchService`) reuse the same `EmployeeFolderResolver` → `SharePointLocationService` → `GraphSharePointService` chain.

---

## 6. Time & attendance subsystem

Country-configurable at the top, employee-resolved at the bottom, with a sharp split between **native RH leave management** and a **separate pointage/attendance service it only configures**.

**Country-level config** (`ADMIN_REGIMES`/`ADMIN_BREAKS`):
- `working_time_regimes` → `WorkingTimeRegime` — per-country schedule templates. `WorkingTimeRegimeController` (`/api/hr/regimes`).
- `break_templates` + `break_legal_rules` — templates are a regime's configured break windows; legal rules are country-mandated minimums keyed by hours worked. `BreakCalculationEngine` combines both plus `pays_weekends`.
- `parametrage_heures_supp_pays` → `ParametrageHSService` — one **active** overtime ruleset per country (new one auto-deactivates the prior one).
- `pays_weekends` / `holidays` — per-country weekend days, public holidays (`HolidayController`, `/api/hr/admin/holidays`).

**Employee-level resolution** (`RegimeResolutionService`, first-match-wins): SEASONAL window (outranks all) → EMPLOYEE_OVERRIDE (`employee_profiles.regime_template_id`) → ROLE_ASSIGNMENT (`regime_role_assignments`) → DEFAULT (country's `is_default` regime). `regime_assignment_history` is the audit trail.

**Leave/congé** (native, fully implemented, **ported from a separate legacy "Timesheet" application on 2026-09-28**):
- `absences` → `LeaveRequest.java`. Every actual SQL caller hits `[dbo].[absences]` (a stale Javadoc line says `leave_requests` — ignore it, `absences` is correct).
- `AbsenceTypes` → admin-configurable leave-type catalog. Verified-enforced: `tracksBalance`+`balanceField`, `maxDays`, `requiresJustification`, `includedInHrStats`, `managerCanView`. Verified-**inert** (stored, never read): `allowedGender`, `approverResolutionStrategy`.
- `absence_modification_requests`: **zero code references** — unimplemented/dead.
- `leave_balances`: **zero code references.** Balances are read directly off `Users.soldeConge`/`soldeMaladie`/`soldeTeletravail` (`LeaveRequestService.balancesOf()`); "days taken" is computed on the fly from approved `absences` rows. **This table is unused.**
- `RoleApprovableAbsenceTypes` — **live**, a role↔absence-type N:M join managed entirely via raw SQL (because `Role` carries no JPA relations).
- `autorisations` and `teletravails`: **zero functional code references** — sibling modules **not yet ported** from the legacy Timesheet app (`DemandeEtat` enum `EN_ATTENTE`/`VALIDE`/`REFUSE`/`ARCHIVE` is shared across all three tables' `etatDemande` column, confirming they're schema-identical siblings the UI/service just hasn't been built for yet).

**`timesheet_records`**: **zero code references anywhere in daf360-rh-service.** Confident conclusion: belongs to the separate `daf360-log-service` / pointage application. rh-service only *configures* attendance parameters (regimes, break rules, overtime rules) and exposes them via `InternalRegimeController` (`GET /api/hr/internal/users/{userId}/regime`, `X-Internal-Key`-authenticated, explicitly documented as serving **log-service's presence scheduler**). Don't conflate this with the native `absences`/congé module.

---

## 7. Payroll-adjacent remnants in `daf360_hr` — definitive answer

- **`fiches_paie`, `payroll_components`, `payroll_simulations`: zero references anywhere** in `daf360-rh-service` — dead/legacy from this service's point of view (cannot speak to other services).
- **`parameter_sets`: live, but narrower than it might look, with one dead code path.** `ParameterSetController` (`/api/hr/admin/parameters`, `HR_ADMIN_ROLES`) manages rows like `TAUX_CNSS_EMPLOYE`, `IRPP_BRACKETS`, `DEVISE`, `NB_MOIS_PAIE` — the service's own Javadoc says these were **"formerly in PayrollCalculationEngine."** `ParameterSetService.loadParams()` builds a `PayrollParams` record — **zero callers found anywhere.** The admin screen still lets someone edit/seed these payroll-rate values, but nothing in rh-service actually *consumes* them for a calculation — vestigial, left over from before payroll split into `daf360-payroll-service` (its own independent `parameter_sets` in `DAF360_PAYROLL`).
  **Separately, actively used**: `PdfDocumentService`/`DocumentTemplateService` both run `SELECT cle, valeur FROM parameter_sets WHERE pays_id=? AND cle IN ('DG_NAME','DG_CIN','DG_CIN_DATE','DG_CIN_CITY','DG_TITLE')` — the authorised-signatory ("Directeur Général") details stamped on generated HR documents. Live and important; unrelated to payroll math.
- **`fiches_paie` is NOT the "Payslip Batch" feature.** `PayslipBatchController`/`PayslipBatchService` (`/api/hr/payslips/process-batch`) takes a multi-page PDF produced by an **external** payroll system, extracts a "Matricule" printed on each page (`PayslipMatriculeExtractor`), matches against `employee_profiles.payrollMatricule`, splits into per-employee single-page PDFs, uploads to SharePoint via the same `EmployeeFolderResolver`/`GraphSharePointService` chain document generation uses. **No payslip amount/line item/figure is ever parsed, stored, or written to any DB table.** `MyPayslipController` (`/api/hr/payslips/my`) is the read side (SharePoint list/download, no DB payslip-content table). This confirms the Payslip Batch feature is entirely independent of `fiches_paie`/`payroll_components`/`payroll_simulations`, which remain untouched dead tables.

---

## 8. Notifications / workflow engine

Generic, admin-configurable routing, distinct from the purpose-built offboarding/lifecycle workflows (§2):

- **Event catalog**: `notification_event_types` → `NotificationEventType` (e.g. `EMPLOYEE_STATUS_CHANGED`).
- **Routing rules**: `notification_routing_rules` → `NotificationRoutingRule`, admin-editable via `NotificationRoutingAdminController`. Targeting modes (`NotificationRoutingService`, resolved against `Users`/`Roles`): `ALL` (every active holder of a role in the event's country), `MANAGER` (`Roles.parent_role_id`), `PERMISSION` (every active user whose role carries a given permission), `SUBJECT` (the one user the event is about), `MANAGER_OF_SUBJECT`.
- **Recipients**: `notification_routing_recipients` (in-app, per rule) and `email_routing_recipients` (parallel email-specific list).
- **Delivery**: `notifications` table (in-app bell, `InAppNotifier`); `MailService`/`SmtpMailServiceImpl`/`LogMailServiceImpl` for email. `NotificationController` (`/api/hr/notifications`) is read/mark-read.
- **Generic approval-request layer**: `request_type_catalog` (per-country request types, L1/L2 `approval_level`, `default_sla_days`) → `RequestTypeCatalogController`; `employee_requests` → the actual submitted request, status-tracked, assignable; `request_approvals` → presumably the per-step L2 approval ledger (NOT fully independently verified — flagged as not fully traced). SLA breaches watched by `SlaEscalationScheduler`.

**Dead sub-pieces**:
- **`workflow_tasks`: zero references** — a DTO comment states it outright: onboarding only ever writes a single `workflow_instances` row; the only real task tables are the offboarding ones.
- **`workflow_instances`: write-only, single-purpose.** `WorkflowInstanceService` has exactly one method, `createOnboardingInstance()` — a raw INSERT with `event_type='ONBOARDING'`, `status='OPEN'` hardcoded, **never transitioned afterward**. The "new joiners" dashboard widget actually derives completeness from `employee_documents`, not this table's status. Functions as little more than an onboarding-event marker — **not** the same thing as the generic notification/approval system above despite the similar name.
- **RabbitMQ**: `RabbitMQConfig` declares an exchange (`daf360.hr`) and two queues (`hr.employee.events`, `hr.leave.events`) with a `RabbitTemplate` bean — **zero** `convertAndSend(...)` calls and **zero** `@RabbitListener` anywhere. Configured but entirely unused scaffolding.

---

## 9. Permissions / security model

**Permission catalog**: `common/PermissionCatalog.java` — a plain `public final class` of `String` constants grouped into `PermGroup` records. Its "Module Payroll" group (`PAYROLL_RUN_SIMULATION`, `PAYROLL_APPROVE_PARAMSET`, etc.) explicitly documents that it **"must stay in sync with daf360-payroll-service's own `PermissionCatalog`"** — payroll permission codes are duplicated by convention across the two services' catalogs rather than shared via a library.

**Enforcement**: `@PreAuthorize("hasAuthority('CODE')")` (checked against `SimpleGrantedAuthority` populated straight from the JWT's `permissions` claim — no `ROLE_` prefix, no DB lookup per-request) or `@PreAuthorize("hasPermission(null, 'CODE')")` (routes through `security/HrPermissionEvaluator.java` — functionally equivalent).

**Role tables**:
- `Roles` → `Role.java` — explicitly documented as "shared `[Roles]` table in DAF360_HR (Timesheet-owned — read-mostly)" — rh-service treats this as someone else's table.
- `RolePermissions` (PascalCase) → `RolePermission.java` — the real, enforced table; `CHECK` constraint restricts values to the ~32 codes in `RoleService.ALLOWED_PERMISSIONS` (per the entity's own warning, possibly now out-of-date vs. the real catalog — verify before relying on it).
- **`role_permissions` (lowercase) — confirmed dead**, and the cause is documented precisely in code: Spring Boot's naming strategy rewrites unquoted `@Table`/`@Column` names, so an earlier unquoted `@Table(name="RolePermissions")` got silently remapped to `role_permissions` by Hibernate, creating a second, near-empty, accidental duplicate table — a direct instance of a SQL-Server-naming-strategy bug.
- `RolePaysScope` → `RolePaysScope.java` (V74) — the tenant-scoping table. One row per (role, country); `Roles.showAll=1` means "every country" (rows ignored); no rows falls back to the user's own `Users.pays_id`. Resolved into `PaysScopeContext.Scope(all, paysIds)` **at JWT-parse time** inside `JwtAuthFilter` (reading `paysScopeAll`/`paysIds` claims — resolved by the **portal**, not rh-service, at sign-time), stored in a request-scoped `ThreadLocal`, cleared in `finally`. `TenantService` exposes `getEffectivePaysId()` (legacy single-country collapse) and `getPaysScope()` (correct for list endpoints, honours OWN/LIST/ALL). `ReferenceDataController.readableP()` (§4) is the clearest consumer example.
- **`categorie_fields`, `categorie_roles`, `categories`: all unimplemented in rh-service** — `PermissionCatalog` carries unused constants (`GET_CATEGORIES`/etc., likely carried over from the legacy Timesheet app) but there is no entity, controller, or repository for an actual categories feature.
- **`RoleMapping`, `STG_Roles`: zero references** — likely portal-side or ETL/staging artefacts.
- **`RSS_employee_profiles`**: referenced in one comment to say balances/fields live on a *different* table and should **not** be pulled from here — rh-service knows this table exists (probably a reporting/replica table owned elsewhere) and deliberately avoids it.

---

## 10. Candidate cost approvals & offboarding settlement — finance touchpoints

Both live entirely inside rh-service's own schema; **neither calls out to daf360-payroll-service or daf360-facturation-service from the backend.**

- **`candidate_cost_approvals`** (`CandidateCostApprovalController`, `/api/hr/cost-approvals`, `APPROVE_HIRING_COST`): stores `proposed_salary`, `salaire_net_rh`, `salaire_net_candidat`, `contract_type_code`, and a **`simulation_snapshot`** column — raw JSON, stored verbatim, **never recomputed**. The payroll engine's answer for `proposedSalary` (from `POST /api/payroll/simulations/individual`) is stored verbatim, which confirms: **the FRONTEND calls daf360-payroll-service's simulation endpoint directly**, and only the resulting JSON is handed to rh-service as evidence. rh-service itself never talks to payroll-service for this. Two submission paths feed the same finance queue: a standalone "budget pre-validation" (no `jobOfferId`, gates nothing) and a per-offer-round gate (`jobOfferId` set, blocks salary from reaching the candidate until approved).
- **`offboarding_settlement_lines`**: explicitly documented as **stored, not computed** — two of the three usual lines have no source in rh-service (no leave-balance/congés-payés table, no per-pays convention scale for indemnité de rupture). Only the pro-rata 13th-month figure is derivable and offered as a suggestion (`is_suggested`); everything else is manually typed during offboarding, with no call to payroll-service or facturation-service to compute/validate.

**Net conclusion**: rh-service is the system of record for the approval decision and audit trail in both cases, but actual salary-calculation logic lives in payroll-service and is reached only via the **frontend**, never backend-to-backend. Nothing here crosses into facturation-service.

---

## 11. IT asset management

- **`it_provisioning`** — the onboarding-time IT setup ticket: MS365 email/account status, AD account flags, license checkboxes, status `PENDING→IN_PROGRESS→EMAIL_CREATED→COMPLETED`. `ItProvisioningController` (`/api/hr/it-provisioning`). Hardware columns moved out in V23 to:
- **`it_assets`** — child table of `it_provisioning` (one-to-many, cascade-all), typed against `it_asset_types`, serial numbers/brand-model.
- **`it_asset_assignments`** (V76, **verify present on a given environment**) — the **post-hire, ongoing equipment ledger**: "IT hands the hardware over, RH keeps the record straight." `ItAssetAssignmentController` (`/api/hr/profiles/{id}/it-assets`, `/api/hr/it-assets/{id}`), `RH_MANAGE_IT_ASSETS`.

Relationship: `it_provisioning` created/driven during onboarding; `it_asset_assignments` read/closed during offboarding stage 4.

---

## 12. Auth / JWT setup

`security/JwtAuthFilter.java` tries **all three candidates and picks whichever validates** (not a simple try-then-fallback):
```
1. Authorization: Bearer <token>        (HS256, portal-issued — this is the live path, see shell/infra doc §4)
2. Cookie: daf360_rh = <token>          (HS256 — LEGACY, no longer issued, dead for fresh sessions)
3. Cookie: daf360_access = <token>      (RS256, portal's own access cookie — never carries permissions)
```
Whichever validates first is used; claims (`permissions`, `paysId`, `paysScopeAll`, `paysIds`) come from that one token only. `TenantContext`/`PaysScopeContext` populated in the filter, cleared in `finally`. Internal service-to-service endpoints (`/api/hr/internal/**`, `/api/hr/users-for-sync`, `/api/hr/pays-for-sync`) bypass this chain via `permitAll()` and check an `X-Internal-Key` shared secret inside the controller instead.

**Cross-reference**: see `PLATFORM_SHELL_AND_INFRASTRUCTURE_AUDIT.md` §4 for the full end-to-end picture of where the Bearer token (`rhToken`) actually comes from (portal's `/api/me`) — assume permissions arrive exclusively via that header for any new feature; don't rely on a cookie carrying permissions.

---

## 13. Key controllers (consolidated)

| Controller | Base path | Backs |
|---|---|---|
| `EmployeeProfileController` | `/api/hr/profiles` | Employee directory/profile CRUD, photo, leave-balances sub-resource |
| `EmployeeLifecycleController` | `/api/hr/employees`, `/api/hr/contracts`, `/api/hr/lifecycle` | Contract lifecycle engine (§2) |
| `ContractHistoryController` | `/api/hr/ref/type-contrat`, `/api/hr/profiles/{id}/contrats` | Legacy `historique_contrat_collaborateur` ledger |
| `OnboardingController` | `/api/hr/onboarding` | Onboarding wizard |
| `OffboardingController` | `/api/hr/offboarding` | Offboarding 7-stage wizard |
| `OffboardingCatalogController` | `/api/hr/admin/offboarding-catalog` | Task catalog admin |
| `OffboardingValidatorController` | `/api/hr/admin/offboarding-validators` | Per-country RH validator role config |
| `CandidateController` | `/api/hr/candidates` | Candidate + offer screens |
| `CandidateInterviewController` | `/api/hr/candidates/{id}/interviews`, `/api/hr/interviews/*` | Interview scheduling |
| `InterviewTypeController` | `/api/hr/interview-types` | Interview-type catalog |
| `RecruitmentDemandController` | `/api/hr/recruitment-demands` | Hiring-need approval |
| `CandidateCostApprovalController` | `/api/hr/cost-approvals` | Finance hiring-cost gate |
| `PipelineController` | `/api/hr/pipeline` | Recruitment kanban |
| `ReferenceDataController` | `/api/hr/ref` | Dedicated reference tables + pays timezone/scheduling/holidays |
| `ConfigurableListController` | `/api/hr/lists` | Generic list system |
| `DocumentTemplateController` | `/api/hr/admin/document-templates` | Template CRUD + render |
| `DocumentHistoryController` | `/api/hr/profiles/{id}/documents/history` | SharePoint document-history tab |
| `EmployeeDocumentController` | `/api/hr/profiles/{id}/documents` | Document locker |
| `PdfDocumentController` | `/api/hr/documents` | Generated PDF retrieval |
| `PayslipBatchController` | `/api/hr/payslips` | Monthly payslip split/file |
| `MyPayslipController` | `/api/hr/payslips/my` | Employee's own payslip history |
| `SharePointAdminController` | `/api/hr/sharepoint` | SharePoint path/folder admin |
| `AbsenceController` | `/api/hr/absences` | — |
| `AbsenceTypeController` | `/api/hr/leave/admin/types` | Leave-type catalog admin |
| `LeaveRequestController` | `/api/hr/leave` | Congé requests, balances, approver queue |
| `HolidayController` | `/api/hr/admin/holidays` | Public holidays admin |
| `BreakTemplateController` | `/api/hr/breaks/templates`, `/api/hr/breaks/legal-rules` | |
| `WorkingTimeRegimeController` | `/api/hr/regimes` | |
| `MissionController` | `/api/hr/missions` | Ordres de mission / travel (see note) |
| `ItProvisioningController` | `/api/hr/it-provisioning` | |
| `ItAssetAssignmentController` | `/api/hr/profiles/{id}/it-assets`, `/api/hr/it-assets` | |
| `NotificationController` | `/api/hr/notifications` | In-app bell |
| `NotificationRoutingAdminController` | admin routing rules | |
| `RoleController` | `/api/hr/admin/roles` | Role CRUD, permission assignment, pays-scope, user assignment |
| `PermissionCatalogController` | `/api/hr/admin/permissions` | Serves `PermissionCatalog.GROUPS` to admin UI |
| `AdminUserController` | `/api/hr/admin/users` | User/balance admin |
| `ParameterSetController` | `/api/hr/admin/parameters` | §7 |
| `AuditController` | `/api/hr/audit` | `audit_log` read |
| `DashboardController` | `/api/hr/dashboard` | KPI widgets, new-joiners card |
| `UserSyncController` | `/api/hr` (`/users-for-sync`, `/pays-for-sync`) | Outbound-pull endpoints for facturation/payroll (§16) |
| `InternalPayrollSyncController` | `/api/hr/internal/payroll-sync` | Inbound from payroll-service |
| `InternalSalaryAdvanceController` | `/api/hr/internal/salary-advances`, `/api/hr/internal/notifications` | Inbound from payroll-service |
| `InternalRegimeController` | `/api/hr/internal/users/{userId}/regime` | Inbound from **log-service** |

**Note**: `MissionController`/`MissionService` + domain (`Mission`, `MissionChangeRequest`, `MissionExpense`, `MissionStatusHistory` → tables `missions`, `mission_change_requests`, `mission_expenses`, `mission_status_history`) form a complete "ordres de mission" + "billeterie" (expense/ticketing) module (frontend routes `/missions`, `/billeterie`). **Verify these 4 tables exist on the target environment before building against them** — not confirmed present everywhere, consistent with the hand-applied-migration pattern.

---

## 14. Ports / profiles / Docker

- **Dev port**: `8888` (`app.PORT` env var). **Prod port**: `8891` (different from dev — see shell/infra doc §6).
- **Profiles**: default (SQL Server via `DB_URL`, `ddl-auto: update`) · `dev` (H2 in-memory, `ddl-auto: create-drop`) · `local` (SQL Server on localhost, `ddl-auto: none`, secrets from `.env`) · `prod` (`ddl-auto: none`, CORS from `CORS_ORIGINS` env, PDF service URL defaults to `http://daf360-pdf-service:3000`).
- **Docker**: multi-stage, build on `maven:3.9-eclipse-temurin-21-alpine`, run on `eclipse-temurin:21-jre-alpine` (pom declares Java 17 — minor version mismatch, likely harmless since 17 bytecode runs on a 21 JVM). `TZ=UTC` forced deliberately (per-entity zones come from `pays`/`working_time_regimes`, so the container must stay UTC).
- Spring Boot 4.0.5, Spring Security (JWT via `io.jsonwebtoken`), Spring Data JPA, Spring AMQP (configured-but-unused, §8), Spring Mail, PDFBox, MS Graph SDK.

---

## 15. Frontend (`daf360-rh-frontend`)

**Federation**: native federation, remote name `rh` (exposes only `./Routes`). Dev server port **4203**. Note: backend's default CORS allowed-origin default is `4201` (`application.yml`), and `SecurityConfig.java` hardcodes `4201` too — `4203` is **not** in that hardcoded list, only reachable via env-driven config or because the shell proxies the federated remote (cross-origin CORS doesn't apply at runtime in that case). Worth a manual check if ever running the RH frontend standalone against a local backend.

**Angular 21.2.18**, NgRx 21.1.0, `@ngx-translate/core` 18, shared library `@khalilrebhiitec/daf360` — **package.json wants `^5.3.0` but node_modules is actually stuck on `4.30.0`** as of this audit (2026-10-25) — a confirmed, live version-drift instance (see shell/infra doc §5 for the full pattern and a `npm ci` recommendation).

**Route structure** (children of `HrShellComponent`, `authGuard` + per-route `permissionGuard`):
- `accueil`, `profiles`, `candidates`, `recrutement` (pipeline/kanban), `it-provisioning`, `onboarding`
- `leave` — **confirmed a placeholder stub**: renders only a static "coming soon" title/subtitle, no logic, no service calls.
- `offboarding` (old `/lifecycle` path kept as a redirect)
- `missions`, `billeterie`, `requests`, `requests-history` (old `/recruitment-demands` redirected here)
- `conges` — the **real**, fully-built leave module (inbox, history, settle, display service — moved from the Timesheet application 2026-09-28). **This duplicates `leave` in URL-space intent but is the actually-functional one — target `conges`, not `leave`, in any new leave-related prompt.**
- `admin` — sub-modules: `breaks`, `lists`, `notifications`, `overtime`, `regimes`, `roles`, `sharepoint`, `users`.

**State management**: NgRx used narrowly — only one registered feature state (`provideState('rh', rhProfilesReducer)` + `ProfilesEffects`) for the employee-profiles list/detail. Everything else is plain Angular services + signals/RxJS.

**i18n**: French, English, Arabic (`public/assets/i18n/{fr,en,ar}.json`), loaded eagerly at module-init into an **isolated child `TranslateService`** scoped to the RH subtree — deliberately so RH's translation keys don't clobber the shell host's own i18n store when federated.

**Cross-service calls from the frontend itself** (`environment.ts`): direct base URLs to `portalUrl` (8080), `hrApiUrl` (8888), `shellUrl` (4200), `payrollApiUrl` (8893, "cost simulation engine"), `logApiUrl` (9000, "pointage service — read-only… the break-template form needs the pointage status catalogue"). Confirms §10's finding independently, and additionally reveals the **break-template admin screen also calls the log/pointage service directly** to populate a status dropdown.

---

## 16. Outbound calls FROM daf360-rh-service (backend)

1. **`ModuleSyncService.java`** — genuine outbound call, synchronous from an admin-screen action (user account create/update/deactivate), 8s timeout, caller's own bearer token forwarded:
   - `POST {factApiBaseUrl}/api/fact/ref/users/sync` → facturation-service
   - `POST {payrollApiBaseUrl}/api/payroll/ref/sync` → payroll-service
   Both base URLs default to empty string — **if either env var isn't set, the call is silently skipped and reported as "module not configured," not an error** (deliberate design). Purpose: push an urgent account change ahead of each consumer's own 15-minute pull cycle; non-fatal because the pull catches up regardless.
2. **`PdfClientService.java`** — calls `{pdfServiceUrl}/pdf/api/arx/{templateSlug}` and `/pdf/api/render` on `daf360-pdf-service` (shared infra microservice, also used by payroll/facturation).
3. **`GraphSharePointService`/`GraphCalendarService`** — Microsoft Graph API (external).
4. **Inbound-only, confirmed**: `/api/hr/internal/payroll-sync/*` and `/api/hr/internal/salary-advances/*` called **into** rh-service **by** payroll-service. `/api/hr/internal/users/{userId}/regime` called **by daf360-log-service's presence scheduler**. `/api/hr/users-for-sync` / `/api/hr/pays-for-sync` are the pull-side counterpart to the push above, polled every 15 minutes by **both** facturation-service and payroll-service.
5. The **frontend**, not the backend, calls payroll-service directly for cost simulation (§10) and log-service directly for the pointage status catalogue (§15) — not backend-to-backend calls.

**Summary**: rh-service's only genuine backend-to-backend outbound calls to sibling DAF360 services are the two `ModuleSyncService` pushes. Everything else that looks like "RH talking to payroll" is either payroll/log-service calling *into* RH, or the RH *frontend* calling payroll/log-service directly.

---

## Summary table: confirmed-dead / confirmed-live ambiguous tables

| Table | Status | Evidence |
|---|---|---|
| `fiches_paie` | **Dead** | Zero code references anywhere |
| `payroll_components` | **Dead** | Zero code references anywhere |
| `payroll_simulations` | **Dead** | Zero code references anywhere |
| `parameter_sets` | **Live** (two purposes) | CRUD admin screen + DG-signatory lookup; payroll-rate half (`loadParams()`) has no callers — vestigial |
| `documents_hr` | **Dead** (near-certainly) | No entity/repo/controller; stale comment referencing a non-existent class |
| `document_template_versions` | **Dead** | Zero code references |
| `absence_modification_requests` | **Dead** | Zero code references |
| `leave_balances` | **Dead** | Balances read from `Users.soldeConge/soldeMaladie/soldeTeletravail` |
| `autorisations` | **Dead** (not yet ported) | Mentioned only as a future port of `absences` |
| `teletravails` | **Dead** (not yet ported) | Same as above |
| `categorie_fields` / `categorie_roles` / `categories` | **Dead** | Unused `PermissionCatalog` constants only, no implementation |
| `role_permissions` (lowercase) | **Dead** | Confirmed accidental Hibernate-naming-strategy duplicate of `RolePermissions` |
| `RoleMapping` / `STG_Roles` | **Dead** | Zero code references |
| `RSS_employee_profiles` | **Dead (by design)** | Explicitly avoided — belongs to another module |
| `timesheet_records` | **Not rh-service's** | Zero references; belongs to daf360-log-service |
| `workflow_tasks` | **Dead** | Zero references; confirmed by explicit code comment |
| `workflow_instances` | **Live but minimal** | Write-only single marker row per onboarding; no state machine |
| `RoleApprovableAbsenceTypes` | **Live** | Raw-SQL N:M join, actively used |
| `portal_events` | **Dead (from rh-service)** | Zero code references — likely portal-owned |

**Tables used by code but needing direct DB verification** (consistent with "no Flyway, applied by hand"): `document_types` (V87), `it_asset_assignments` (V76), `sharepoint_delta_state`, and the entire Missions module (`missions`, `mission_change_requests`, `mission_expenses`, `mission_status_history`).

---

## Open questions / not fully verified

- The full `request_approvals` L2 approval flow inside `EmployeeRequestService` — not fully traced line-by-line.
- A complete endpoint-by-endpoint dump of `EmployeeProfileController`/`AdminUserController` (large files, only sampled).
