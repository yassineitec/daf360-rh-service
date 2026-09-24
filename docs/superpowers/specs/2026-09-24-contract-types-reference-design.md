# Contract Types Reference — Design

## Goal

Replace four independently drifted, hardcoded contract-type lists (spread across `daf360-rh-service`, `daf360-rh-frontend`, and `daf360-payroll-service`/`daf360-payroll-frontend`) with a single, per-country, database-backed reference list in `daf360-rh-service`, consumed dynamically by every screen that currently hardcodes its own copy.

## Current State (confirmed by code and DB reading, 2026-09-24)

Four independent hardcoded contract-type lists exist today, all out of sync with each other:

1. `daf360-payroll-frontend/src/app/modules/parameter-sets/parameter-sets.component.ts:34` — `readonly contractTypes = ['CDI', 'CDD', 'STAGE', 'CIVP']` (4 values). Used both for a `<select>` per social-charge-rate row and for 4 hardcoded checkboxes per payroll rubrique (`ctCDI`/`ctCDD`/`ctSTAGE`/`ctCIVP` form controls, `parameter-sets.component.html:445-448`).
2. `daf360-payroll-frontend/src/app/modules/employee-config/employee-config.component.ts:14` — `const CONTRACT_TYPES = ['CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT']` (6 values).
3. `daf360-rh-frontend/src/app/modules/profiles/detail-sections/remuneration-section.component.ts:15` — the exact same 6-value array as #2, byte-for-byte duplicated (this is the "Type de Contrat" dropdown on the profile detail page's Rémunération tab).
4. `daf360-rh-frontend/src/app/modules/profiles/profile-labels.ts:21-24` — an 11-value `CONTRACT_CODES` `Set` (`PERMANENT, FIXED_TERM, INTERN, CONSULTANT, CDI, CDD, CIVP, STAGE, DETACHEMENT, PORTAGE, FREELANCE`) used only to decide whether a code gets a translated label or renders raw — not a selectable list.

`employee_profiles.contract_type` (rh-service, table backing `EmployeeProfile.java`) is a free-text `varchar(50)` with two generations of real values: legacy (`CDI, CDD, CIVP, STAGE, DETACHEMENT, PORTAGE, FREELANCE`) and current (what the onboarding wizard writes today: `PERMANENT, FIXED_TERM, INTERN, CONSULTANT`). Real counts in the dev DB: `CDI`→144, `CIVP`→12, `null`→2, `PERMANENT`→1.

An existing `contract_type_config` table (`ContractTypeConfig.java`) already exists in rh-service, keyed by `(pays_id, contract_type_code)`, but it models per-country **lifecycle rules** (trial period days, CIVP/ANETI-specific fields, STAGE duration limits) for 6 of the 11 codes — a different concern from "which types are selectable," has no label fields, and is currently empty in the dev DB (its own comment claims seeding for pays_id=179 that never actually landed here). It is not reused for this feature; conflating "has lifecycle rules configured" with "is a valid selectable type" would be the wrong model, and would leave 5 of the 11 desired codes unrepresentable.

Real employee data exists for exactly two countries: **Tunisia** (pays_id 179, 112 employees) and **Egypt** (pays_id 53, 47 employees). `ContractTypeConfig`'s own field names (`civp_aneti_required`, `civp_max_age`) confirm CIVP is a Tunisia-specific scheme (ANETI is the Tunisian national employment agency) — not universal.

## Decisions (confirmed with the user)

- **No changes to existing employee records.** `employee_profiles.contract_type` keeps its current values exactly as-is; this feature only adds a new reference list and rewires four screens to read from it.
- **Real DB-backed reference table**, not a hardcoded enum — editable later via an admin screen without a redeploy (no such admin screen is built in this pass; see Non-Goals).
- **Per-country list**, not global — because CIVP is Tunisia-specific and a global list would wrongly offer it for every country.
- **Match rh-service's full known domain**: all 11 codes ever seen anywhere in the codebase (both generations, all four lists' union) are the candidate set, not just the 4 the user first mentioned.

## Data Model

New table in `daf360-rh-service` (new Flyway migration, next available version number — to be confirmed at plan-execution time against the real migration directory):

```sql
CREATE TABLE contract_types (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    pays_id       BIGINT NOT NULL,
    code          VARCHAR(30) NOT NULL,
    label_fr      NVARCHAR(100) NOT NULL,
    label_en      NVARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_active     BIT NOT NULL DEFAULT 1,
    created_at    DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT UQ_contract_types_pays_code UNIQUE (pays_id, code)
);
```

**Seed data** (via the same migration — no admin UI in this pass):

| Code | Label FR | Label EN | Tunisia (179) | Egypt (53) |
|---|---|---|---|---|
| CDI | CDI | Permanent contract | ✓ | ✓ |
| CDD | CDD | Fixed-term contract | ✓ | ✓ |
| CIVP | CIVP | CIVP (Tunisia) | ✓ | — |
| STAGE | Stage | Internship | ✓ | ✓ |
| FREELANCE | Freelance | Freelance | ✓ | ✓ |
| DETACHEMENT | Détachement | Secondment | ✓ | ✓ |
| PORTAGE | Portage salarial | Umbrella employment | ✓ | ✓ |
| PERMANENT | Permanent | Permanent | ✓ | ✓ |
| FIXED_TERM | Durée déterminée | Fixed term | ✓ | ✓ |
| INTERN | Stagiaire | Intern | ✓ | ✓ |
| CONSULTANT | Consultant | Consultant | ✓ | ✓ |

**This table is the user's own best guess, not a verified legal fact per country** — it should be sanity-checked against real local labor-law categories before being treated as final; it's easy to correct later since it's just data, not code.

## Backend (daf360-rh-service)

- New entity `ContractType` mapping the table above, new `ContractTypeRepository` with `findByPaysIdAndIsActiveTrueOrderByDisplayOrderAsc(paysId)`.
- New endpoint `GET /api/hr/contract-types?paysId={id}` → `List<ContractTypeDto(code, labelFr, labelEn)>`. No special permission beyond being authenticated (matches how other simple reference-data GETs in this codebase are exposed) — this is read-only reference data, not sensitive.

## Frontend Consumption

- **daf360-payroll-frontend**: a new small service method (`getContractTypes(paysId)`) calling rh-service directly — the same direct-frontend-to-rh-service pattern `HrProfileService.searchEmployees` already uses, so no payroll-backend change is needed at all (payroll-service's own `contract_type` columns are already unvalidated free text; this feature doesn't change that).
  - `parameter-sets.component.ts`: replace the hardcoded `contractTypes` array with a signal populated when `load()` runs for a given `paysId`. The `<select formControlName="contractType">` iterates the fetched list unchanged in shape.
  - The 4 fixed rubrique checkboxes (`ctCDI`/`ctCDD`/`ctSTAGE`/`ctCIVP`) become a dynamic loop over the fetched list: `makeRubriqueGroup()` builds one boolean control per fetched code instead of 4 fixed named ones, the template `@for`s over the fetched list to render one checkbox each, and `saveRubriques()`'s mapping (currently `['CDI','CDD','STAGE','CIVP']` at line 237) iterates the fetched list instead.
  - `employee-config.component.ts`: replace its own hardcoded `CONTRACT_TYPES` with the same per-paysId fetch.
- **daf360-rh-frontend**: `remuneration-section.component.ts` replaces its hardcoded 6-value array with the same fetch (same-origin call to its own backend).
- Raw `code` continues to be what's displayed in dropdowns/checkboxes (matching today's existing UX — no visual redesign in this pass), even though `labelFr`/`labelEn` are stored and returned for future use.
- Fetch failures follow this codebase's existing convention for reference-data calls: fall back to an empty list via `catchError(() => of([]))`, never block the rest of the screen.

## Non-Goals

- No admin UI to create/edit/deactivate contract types — seeded once via migration; future edits are a hand-applied SQL change or a later admin screen, not built here.
- No migration/normalization of existing `employee_profiles.contract_type` values on real employees.
- No change to `payroll_rubriques_legacy`, `simulation_results.contract_type`, or `calibration_variances.contract_type` — those are historical/audit columns, out of scope.
- No visual redesign of the affected dropdowns/checkboxes beyond making their source dynamic.

## Testing

- Backend: unit test for the repository query (per-country filtering, `is_active` filtering, ordering) and a controller test for the new endpoint.
- Frontend: component tests confirming `parameter-sets.component.ts` and `employee-config.component.ts` render options from a mocked fetch response instead of a hardcoded array, and that the rubrique checkbox list length matches the fetched list length (not hardcoded to 4).
