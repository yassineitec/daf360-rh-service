# Employee Lifecycle Engine — DAF360 RH

> **Tickets:** D3-95 → D3-105  
> **Backend:** Spring Boot 4.0.5 / Java 17 (`rh-service`)  
> **Frontend:** Angular 21 (`daf360-rh-frontend`)  
> **Implemented:** 2026-06-18

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Database Schema](#3-database-schema)
4. [Backend — Domain Entities](#4-backend--domain-entities)
5. [Backend — State Machine](#5-backend--state-machine)
6. [Backend — Service Layer](#6-backend--service-layer)
7. [Backend — CRON Alert Job](#7-backend--cron-alert-job)
8. [Backend — REST API](#8-backend--rest-api)
9. [Backend — Unit Tests](#9-backend--unit-tests)
10. [Frontend — TypeScript Models](#10-frontend--typescript-models)
11. [Frontend — HTTP Service](#11-frontend--http-service)
12. [Frontend — New Contract Form](#12-frontend--new-contract-form)
13. [Frontend — Profile Detail Integration](#13-frontend--profile-detail-integration)
14. [Frontend — Sidebar Alert Badge](#14-frontend--sidebar-alert-badge)
15. [User Guide](#15-user-guide)
16. [Pending Work](#16-pending-work)

---

## 1. Overview

The **Employee Lifecycle Engine** manages the full contractual lifecycle of an employee from recruitment to archival. It replaces the older flat `contract_type` / `contract_end_date` fields on `employee_profiles` with a proper contract state machine.

### What it covers

| Ticket | Capability |
|--------|-----------|
| D3-95  | Domain model — 4 new DB tables, 6 contract types |
| D3-96  | Per-type state machine (`LifecycleStateMachine`) |
| D3-97  | Contract creation with trial period calculation |
| D3-98  | Trial period validation (approve → ACTIF_CONFIRME / reject → RESILIE) |
| D3-99  | CDD renewal (new end date, increments `cdd_renouvellement_count`) |
| D3-100 | CDD → CDI conversion (new CDI contract linked to parent CDD) |
| D3-101 | General state transition with append-only audit log |
| D3-102 | Daily CRON at 08:00 — sends alerts 30 days before expiry |
| D3-103 | Simultaneous notifications to RH + IT + DIRECTEUR_PAYS |
| D3-104 | Append-only `employee_lifecycle_transitions` log, `dossier_locked` on terminal state |
| D3-105 | Per-country configurable contract rules (`contract_type_config`) |

### Contract types

| Code | Label | End date required | Trial period |
|------|-------|:-----------------:|:------------:|
| `CDI` | Contrat à Durée Indéterminée | No | Yes (3 or 6 months) |
| `CDD` | Contrat à Durée Déterminée | Yes | No |
| `CIVP` | Contrat d'Insertion à la Vie Professionnelle | Yes | No |
| `STAGE` | Stage | Yes | No |
| `DETACHEMENT` | Détachement | Yes | No |
| `PORTAGE` | Portage salarial | No | No |

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Angular Frontend                         │
│                                                                 │
│  profile-detail.component          hr-shell.component           │
│  ├── "Contrats & Cycle de vie"     └── lifecycleAlertCount      │
│  │   collapsible section               badge on nav item        │
│  │   ├── Contract list cards                                     │
│  │   ├── Trial / CDD / CDI modals                               │
│  │   └── Transition history timeline                            │
│  └── new-contract-form.component                                │
│      (6-type modal)                                             │
│                                                                 │
│  ContractLifecycleService  ──── HTTP ───►  rh-service :8082     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot rh-service                       │
│                                                                 │
│  EmployeeLifecycleController  (14 endpoints)                    │
│  └── EmployeeLifecycleService                                   │
│      ├── LifecycleStateMachine  (transition guard)              │
│      ├── ContractTypeConfigRepository  (per-country rules)      │
│      ├── EmployeeContractRepository                             │
│      ├── EmployeeLifecycleTransitionRepository  (audit log)     │
│      └── EmployeeLifecycleAlertRepository                       │
│                                                                 │
│  LifecycleAlertJob  (@Scheduled 08:00 daily)                    │
│  ├── sendAlert()  → in-app notification + email                 │
│  └── planNewAlerts()  → 30-day rolling window                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    SQL Server (daf360_hr)                       │
│                                                                 │
│  employee_contracts            (one row per contract)           │
│  employee_lifecycle_transitions  (append-only audit)           │
│  employee_lifecycle_alerts     (scheduled alert queue)         │
│  contract_type_config          (per-country rules)             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Database Schema

> **Note:** A Flyway migration script (`V33__employee_lifecycle_engine.sql`) needs to be created before first deployment. The SQL below is the reference.

### 3.1 `employee_contracts`

```sql
CREATE TABLE [dbo].[employee_contracts] (
    id                          BIGINT IDENTITY(1,1) PRIMARY KEY,
    employee_profile_id         BIGINT NOT NULL
        REFERENCES [dbo].[employee_profiles](id),
    pays_id                     BIGINT NOT NULL,
    contract_type_code          NVARCHAR(30)  NOT NULL,   -- CDI|CDD|CIVP|STAGE|DETACHEMENT|PORTAGE
    current_status_code         NVARCHAR(50)  NOT NULL,
    date_debut                  DATE NOT NULL,
    date_fin_prevue             DATE,
    date_fin_reelle             DATE,
    date_fin_periode_essai      DATE,
    periode_essai_renouvelee    BIT NOT NULL DEFAULT 0,
    date_fin_pe_renouvellement  DATE,
    end_reason_code             NVARCHAR(50),
    end_notes                   NVARCHAR(1000),
    reference_contrat           NVARCHAR(100),
    -- CIVP
    civp_aneti_reference        NVARCHAR(100),
    civp_convention_date        DATE,
    -- STAGE
    stage_ecole                 NVARCHAR(255),
    stage_tuteur_id             BIGINT,                   -- raw FK to Users.id
    stage_convention_signee     BIT NOT NULL DEFAULT 0,
    -- PORTAGE
    freelance_tjm               DECIMAL(18,3),
    freelance_devise            NVARCHAR(10),
    freelance_societe           NVARCHAR(255),
    -- DETACHEMENT
    detachement_entite_origine_id  BIGINT,
    detachement_entite_accueil_id  BIGINT,
    detachement_retour_prevu    DATE,
    -- CDD renewal chain
    cdd_renouvellement_count    INT NOT NULL DEFAULT 0,
    cdd_contrat_parent_id       BIGINT REFERENCES [dbo].[employee_contracts](id),
    avenant_parent_id           BIGINT REFERENCES [dbo].[employee_contracts](id),
    -- Flags
    is_active                   BIT NOT NULL DEFAULT 1,
    is_archived                 BIT NOT NULL DEFAULT 0,
    dossier_locked              BIT NOT NULL DEFAULT 0,   -- set true on terminal states
    created_by                  BIGINT,
    created_at                  DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    updated_at                  DATETIMEOFFSET(6)
);
CREATE INDEX IX_EC_ProfileId ON [dbo].[employee_contracts](employee_profile_id);
CREATE INDEX IX_EC_PaysId    ON [dbo].[employee_contracts](pays_id);
CREATE INDEX IX_EC_Active     ON [dbo].[employee_contracts](is_active)
    INCLUDE (employee_profile_id, contract_type_code, current_status_code, date_fin_prevue);
```

### 3.2 `employee_lifecycle_transitions` (append-only audit)

```sql
CREATE TABLE [dbo].[employee_lifecycle_transitions] (
    id                    BIGINT IDENTITY(1,1) PRIMARY KEY,
    contract_id           BIGINT NOT NULL
        REFERENCES [dbo].[employee_contracts](id),
    employee_profile_id   BIGINT NOT NULL,
    statut_avant          NVARCHAR(50),
    statut_apres          NVARCHAR(50) NOT NULL,
    action_code           NVARCHAR(50) NOT NULL,
    triggered_by_user_id  BIGINT NOT NULL,
    triggered_at          DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    commentaire           NVARCHAR(500),
    document_reference    NVARCHAR(255),
    metadata              NVARCHAR(1000)
);
-- NEVER UPDATE OR DELETE rows from this table (D3-104)
CREATE INDEX IX_ELT_ContractId ON [dbo].[employee_lifecycle_transitions](contract_id);
CREATE INDEX IX_ELT_ProfileId  ON [dbo].[employee_lifecycle_transitions](employee_profile_id);
```

### 3.3 `employee_lifecycle_alerts`

```sql
CREATE TABLE [dbo].[employee_lifecycle_alerts] (
    id                    BIGINT IDENTITY(1,1) PRIMARY KEY,
    contract_id           BIGINT NOT NULL
        REFERENCES [dbo].[employee_contracts](id),
    employee_profile_id   BIGINT NOT NULL,
    alert_type            NVARCHAR(50)  NOT NULL,
    alert_date            DATE NOT NULL,
    target_date           DATE NOT NULL,
    recipients            NVARCHAR(500) NOT NULL,  -- JSON array: ["RH","IT","DIRECTEUR_PAYS"]
    is_sent               BIT NOT NULL DEFAULT 0,
    sent_at               DATETIMEOFFSET(6),
    is_acknowledged       BIT NOT NULL DEFAULT 0,
    acknowledged_by       BIGINT,
    acknowledged_at       DATETIMEOFFSET(6)
);
-- Partial index — only unsent alerts scanned by daily CRON
CREATE INDEX IX_ELA_AlertDate ON [dbo].[employee_lifecycle_alerts](alert_date)
    WHERE is_sent = 0;
```

### 3.4 `contract_type_config` (per-country rules)

```sql
CREATE TABLE [dbo].[contract_type_config] (
    id                              BIGINT IDENTITY(1,1) PRIMARY KEY,
    pays_id                         BIGINT NOT NULL,
    contract_type_code              NVARCHAR(30) NOT NULL,
    trial_period_days_standard      INT,          -- CDI: 90 jours
    trial_period_days_manager       INT,          -- CDI encadrant: 180 jours
    trial_period_renewable          BIT NOT NULL DEFAULT 0,
    alert_days_before_expiry        INT NOT NULL DEFAULT 30,
    indemnity_rate_pct              DECIMAL(6,4),
    indemnity_applicable            BIT NOT NULL DEFAULT 0,
    civp_max_age                    INT,          -- 30 ans (Tunisie)
    civp_max_duration_months        INT,          -- 12 mois
    civp_aneti_required             BIT NOT NULL DEFAULT 0,
    stage_max_duration_months       INT,          -- 6 mois
    stage_min_gratification_months  INT,
    created_at                      DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    updated_at                      DATETIMEOFFSET(6),
    updated_by                      BIGINT,
    CONSTRAINT UQ_CTC_PaysType UNIQUE (pays_id, contract_type_code)
);

-- Seed for Tunisia (pays_id = 179)
INSERT INTO [dbo].[contract_type_config]
    (pays_id, contract_type_code, trial_period_days_standard, trial_period_days_manager,
     trial_period_renewable, alert_days_before_expiry, indemnity_applicable,
     civp_max_age, civp_max_duration_months, civp_aneti_required,
     stage_max_duration_months)
VALUES
    (179, 'CDI',         90,   180,  1, 30, 0, NULL, NULL, 0, NULL),
    (179, 'CDD',         NULL, NULL, 0, 30, 1, NULL, NULL, 0, NULL),
    (179, 'CIVP',        NULL, NULL, 0, 30, 0,   30,   12, 1, NULL),
    (179, 'STAGE',       NULL, NULL, 0, 30, 0, NULL, NULL, 0,    6),
    (179, 'DETACHEMENT', NULL, NULL, 0, 30, 0, NULL, NULL, 0, NULL),
    (179, 'PORTAGE',     NULL, NULL, 0, 30, 0, NULL, NULL, 0, NULL);
```

---

## 4. Backend — Domain Entities

All four entities live in `src/main/java/com/daf360/rh/domain/`.

### EmployeeContract.java

Key design points:
- `contractTypeCode` and `currentStatusCode` are plain strings (not enums) — validated by `LifecycleStateMachine`
- Self-referential nullable FKs (`cddContratParent`, `avenantParent`) — no bootstrap issue
- `dossierLocked = true` is set on terminal states — prevents all future transitions
- `NEVER` hard-delete rows — use `is_active = false` instead

```java
@Entity @Table(name = "employee_contracts")
public class EmployeeContract {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_profile_id", nullable = false)
    private EmployeeProfile employeeProfile;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type_code", nullable = false, length = 30)
    private String contractTypeCode;      // CDI|CDD|CIVP|STAGE|DETACHEMENT|PORTAGE

    @Column(name = "current_status_code", nullable = false, length = 50)
    private String currentStatusCode;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin_prevue")
    private LocalDate dateFinPrevue;

    @Column(name = "date_fin_periode_essai")
    private LocalDate dateFinPeriodeEssai;

    @Column(name = "dossier_locked", nullable = false)
    @Builder.Default private Boolean dossierLocked = false;

    // CDD self-referential chain
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cdd_contrat_parent_id")
    private EmployeeContract cddContratParent;

    // ... (see full entity file)
}
```

### EmployeeLifecycleTransition.java

Append-only audit table. **Never update or delete rows.**

```java
@Entity @Table(name = "employee_lifecycle_transitions")
public class EmployeeLifecycleTransition {
    @Column(name = "statut_avant", length = 50)
    private String statutAvant;      // null for contract creation

    @Column(name = "statut_apres", nullable = false, length = 50)
    private String statutApres;

    @Column(name = "action_code", nullable = false, length = 50)
    private String actionCode;

    @Column(name = "triggered_by_user_id", nullable = false)
    private Long triggeredByUserId;

    @Column(name = "triggered_at", nullable = false)
    @Builder.Default
    private OffsetDateTime triggeredAt = OffsetDateTime.now();
}
```

### EmployeeLifecycleAlert.java

```java
@Entity @Table(name = "employee_lifecycle_alerts")
public class EmployeeLifecycleAlert {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private EmployeeContract contract;

    @Column(name = "alert_date", nullable = false)
    private LocalDate alertDate;       // when to send

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;      // contract expiry date

    @Column(name = "recipients", nullable = false, length = 500)
    private String recipients;         // JSON array: ["RH","IT","DIRECTEUR_PAYS"]

    @Column(name = "is_sent", nullable = false)
    @Builder.Default private Boolean isSent = false;
}
```

### ContractTypeConfig.java

Per-country configuration. Query: `findByPaysIdAndContractTypeCode(paysId, typeCode)`.

```java
@Entity @Table(name = "contract_type_config")
public class ContractTypeConfig {
    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "contract_type_code", nullable = false, length = 30)
    private String contractTypeCode;

    @Column(name = "trial_period_days_standard")
    private Integer trialPeriodDaysStandard;   // 90 for CDI (Tunisia)

    @Column(name = "trial_period_days_manager")
    private Integer trialPeriodDaysManager;    // 180 for CDI manager (Tunisia)

    @Column(name = "alert_days_before_expiry", nullable = false)
    @Builder.Default private Integer alertDaysBeforeExpiry = 30;
}
```

---

## 5. Backend — State Machine

**File:** `src/main/java/com/daf360/rh/lifecycle/LifecycleStateMachine.java`

Each contract type has its own set of allowed transitions. The state machine is queried before any transition is applied.

```java
@Component
public class LifecycleStateMachine {

    public List<String> getAllowedTransitions(String contractType, String currentStatus) {
        return switch (contractType) {

            case "CDI" -> switch (currentStatus) {
                case "RECRUTEMENT"   -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI" -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"         -> List.of("ACTIF", "SUSPENDU", "FIN_CONTRAT", "RETRAITE");
                case "SUSPENDU"      -> List.of("ACTIF", "FIN_CONTRAT");
                case "FIN_CONTRAT"   -> List.of("INACTIF");
                case "RETRAITE"      -> List.of("INACTIF");
                default              -> List.of();
            };

            case "CDD" -> switch (currentStatus) {
                case "RECRUTEMENT"        -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI"      -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"              -> List.of("RENOUVELLEMENT_CDD", "CONVERSION_CDI", "FIN_CONTRAT");
                case "RENOUVELLEMENT_CDD" -> List.of("ACTIF", "FIN_CONTRAT");
                case "CONVERSION_CDI"     -> List.of("ACTIF");
                case "FIN_CONTRAT"        -> List.of("INACTIF");
                default                   -> List.of();
            };

            case "CIVP" -> switch (currentStatus) {
                case "RECRUTEMENT"   -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI" -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"         -> List.of("CONVERSION_CDI", "FIN_CONTRAT");
                case "FIN_CONTRAT"   -> List.of("INACTIF");
                default              -> List.of();
            };

            case "STAGE" -> switch (currentStatus) {
                case "RECRUTEMENT"       -> List.of("CONVENTION_SIGNEE");
                case "CONVENTION_SIGNEE" -> List.of("STAGE_ACTIF");
                case "STAGE_ACTIF"       -> List.of("FIN_STAGE", "FIN_CONTRAT");
                case "FIN_STAGE"         -> List.of("INACTIF");
                default                  -> List.of();
            };

            case "DETACHEMENT" -> switch (currentStatus) {
                case "ACTIF"                  -> List.of("ACCORD_DETACHEMENT");
                case "ACCORD_DETACHEMENT"     -> List.of("DETACHEMENT_ACTIF");
                case "DETACHEMENT_ACTIF"      -> List.of("RETOUR_ENTITE_A",
                                                          "INTEGRATION_DEFINITIVE", "FIN_CONTRAT");
                case "RETOUR_ENTITE_A"        -> List.of("ACTIF");
                case "INTEGRATION_DEFINITIVE" -> List.of("INACTIF");
                default                       -> List.of();
            };

            default -> List.of();
        };
    }

    public boolean isTransitionAllowed(String contractType, String fromStatus, String toStatus) {
        return getAllowedTransitions(contractType, fromStatus).contains(toStatus);
    }
}
```

---

## 6. Backend — Service Layer

**File:** `src/main/java/com/daf360/rh/lifecycle/EmployeeLifecycleService.java`

### Key methods

| Method | Description |
|--------|-------------|
| `createContract(dto, actorId)` | Creates a new contract; calculates trial period end date from `ContractTypeConfig` |
| `transitionState(contractId, dto, actorId)` | Validates transition via state machine, saves, logs to audit table |
| `validateTrialPeriod(contractId, dto, actorId)` | Approve → `ACTIF_CONFIRME`; reject → `RESILIE` + `dossierLocked=true` |
| `renewCDD(contractId, dto, actorId)` | Updates `date_fin_prevue`, increments `cdd_renouvellement_count` |
| `convertToCDI(contractId, dto, actorId)` | Creates a new CDI contract; closes parent CDD with `CONVERSION_CDI` |
| `planContractAlerts(contract, config)` | Inserts `EmployeeLifecycleAlert` rows for the configured alert window |
| `getAlerts(paysId, acknowledged)` | Lists alerts for a country, filterable by acknowledged status |
| `acknowledgeAlert(alertId, actorId)` | Marks alert acknowledged |
| `loadEmployeeName(profileId)` | Package-private — used by `LifecycleAlertJob` in same package |

### Trial period calculation (CDI)

```java
// From createContract():
if ("CDI".equals(dto.getContractTypeCode())) {
    int trialDays = dto.isManagerProfile()
        ? config.getTrialPeriodDaysManager()   // 180
        : config.getTrialPeriodDaysStandard();  // 90
    contract.setDateFinPeriodeEssai(dto.getDateDebut().plusDays(trialDays));
    contract.setCurrentStatusCode("PERIODE_ESSAI");
} else {
    contract.setCurrentStatusCode("RECRUTEMENT");
}
```

### Terminal state lock

```java
private static final List<String> TERMINAL_STATUSES = List.of(
    "INACTIF", "FIN_CONTRAT", "FIN_STAGE", "FIN_MISSION",
    "RESILIATION", "RETRAITE", "RUPTURE_PE"
);

// In transitionState():
if (Boolean.TRUE.equals(contract.getDossierLocked())) {
    throw new BusinessRuleException("Ce contrat est verrouillé (état terminal atteint).");
}
// After transition:
if (TERMINAL_STATUSES.contains(dto.getNewStatus())) {
    contract.setDossierLocked(true);
    contract.setIsActive(false);
}
```

### Audit log (every transition)

```java
transitionRepo.save(EmployeeLifecycleTransition.builder()
    .contract(contract)
    .employeeProfileId(contract.getEmployeeProfile().getId())
    .statutAvant(previousStatus)
    .statutApres(dto.getNewStatus())
    .actionCode(dto.getActionCode())
    .triggeredByUserId(actorId != null ? actorId : 0L)
    .commentaire(dto.getCommentaire())
    .documentReference(dto.getDocumentReference())
    .build());
```

---

## 7. Backend — CRON Alert Job

**File:** `src/main/java/com/daf360/rh/lifecycle/LifecycleAlertJob.java`

Runs daily at 08:00. Two phases:

### Phase 1 — send pending alerts

```java
@Scheduled(cron = "0 0 8 * * ?")
@Transactional
public void processLifecycleAlerts() {
    List<EmployeeLifecycleAlert> pending = alertRepo.findPendingAlerts(today);

    for (EmployeeLifecycleAlert alert : pending) {
        sendAlert(alert);       // in-app + email to RH + IT + DIRECTEUR_PAYS
        alert.setIsSent(true);
        alert.setSentAt(OffsetDateTime.now());
        alertRepo.save(alert);
    }

    planNewAlerts(today);       // roll the 30-day window forward
}
```

### Phase 2 — send to multiple roles simultaneously (D3-103)

```java
void sendAlert(EmployeeLifecycleAlert alert) {
    // 1. Resolve recipients from JSON field: ["RH","IT","DIRECTEUR_PAYS"]
    List<String> recipientRoles = parseRecipients(alert.getRecipients());

    // 2. Map role → permission
    for (String role : recipientRoles) {
        String permission = roleToPermission(role);  // see table below
        List<Map<String, Object>> users = jdbc.queryForList(USERS_WITH_PERM_SQL, permission, paysId);

        // 3. For each user: in-app notification + email
        for (Map<String, Object> row : users) {
            jdbc.update(INSERT_NOTIF_SQL, uid, "RH", title, body);
            mailService.sendRoutedEmail(List.of(email), ...);
        }
    }
}
```

### Role → Permission mapping

| Role | Permission |
|------|-----------|
| `RH` | `RH_VIEW_CONTRACTS` |
| `IT` | `RH_MANAGE_LIFECYCLE` |
| `DIRECTEUR_PAYS` | `RH_APPROVE_RECRUITMENT_DEMAND` |

### Phase 2 — plan new alerts

```java
void planNewAlerts(LocalDate today) {
    // Find all contracts expiring within the next 30 days
    List<EmployeeContract> expiring =
        contractRepo.findExpiringContracts(today, today.plusDays(30));

    for (EmployeeContract c : expiring) {
        configRepo.findByPaysIdAndContractTypeCode(c.getPaysId(), c.getContractTypeCode())
            .ifPresent(config -> lifecycleService.planContractAlerts(c, config));
    }
}
```

---

## 8. Backend — REST API

**File:** `src/main/java/com/daf360/rh/controller/EmployeeLifecycleController.java`

Base URL: `http://localhost:8082`

### Employee contracts

| Method | URL | Auth | Description |
|--------|-----|------|-------------|
| `GET` | `/api/hr/employees/{id}/contracts` | JWT | List all contracts for an employee |
| `POST` | `/api/hr/employees/{id}/contracts` | JWT | Create a new contract |
| `GET` | `/api/hr/employees/{id}/lifecycle-history` | JWT | Full transition audit history |

**POST /api/hr/employees/{id}/contracts — request body:**

```json
{
  "employeeProfileId": 42,
  "paysId": 179,
  "contractTypeCode": "CDI",
  "dateDebut": "2026-07-01",
  "managerProfile": false,
  "referenceContrat": "CTR-2026-001"
}
```

For CDD:
```json
{
  "contractTypeCode": "CDD",
  "dateDebut": "2026-07-01",
  "dateFinPrevue": "2027-06-30"
}
```

For CIVP:
```json
{
  "contractTypeCode": "CIVP",
  "dateDebut": "2026-07-01",
  "dateFinPrevue": "2027-06-30",
  "civpAnetiReference": "ANETI-2026-123",
  "civpConventionDate": "2026-06-28"
}
```

For STAGE:
```json
{
  "contractTypeCode": "STAGE",
  "dateDebut": "2026-07-01",
  "dateFinPrevue": "2026-12-31",
  "stageEcole": "ESPRIT Tunis",
  "stageConventionSignee": true
}
```

For DETACHEMENT:
```json
{
  "contractTypeCode": "DETACHEMENT",
  "dateDebut": "2026-07-01",
  "dateFinPrevue": "2027-06-30",
  "detachementRetourPrevu": "2027-06-30"
}
```

---

### Single contract operations

| Method | URL | Auth | Description |
|--------|-----|------|-------------|
| `GET` | `/api/hr/contracts/{id}` | JWT | Get contract details |
| `POST` | `/api/hr/contracts/{id}/transition` | JWT | Apply a state transition |
| `POST` | `/api/hr/contracts/{id}/validate-trial` | JWT | Approve or reject trial period |
| `POST` | `/api/hr/contracts/{id}/renew-cdd` | JWT | Renew a CDD with new end date |
| `POST` | `/api/hr/contracts/{id}/convert-to-cdi` | JWT | Convert CDD/CIVP to CDI |
| `GET` | `/api/hr/contracts/{id}/history` | JWT | Transition history for one contract |

**POST /api/hr/contracts/{id}/transition:**
```json
{
  "newStatus": "ACTIF",
  "actionCode": "CONFIRM_TRIAL",
  "commentaire": "Période d'essai validée"
}
```

**POST /api/hr/contracts/{id}/validate-trial:**
```json
{
  "approved": true,
  "commentaire": "Excellente intégration"
}
```
*approved=false* → contract transitions to `RESILIE` and `dossierLocked=true`.

**POST /api/hr/contracts/{id}/renew-cdd:**
```json
{
  "newDateFin": "2027-12-31",
  "commentaire": "Renouvellement annuel"
}
```

**POST /api/hr/contracts/{id}/convert-to-cdi:**
```json
{
  "cdiStartDate": "2027-01-01",
  "commentaire": "Intégration définitive"
}
```

---

### Alerts & Config

| Method | URL | Description |
|--------|-----|-------------|
| `GET` | `/api/hr/lifecycle/alerts?paysId=179` | List alerts for a country |
| `GET` | `/api/hr/lifecycle/alerts?paysId=179&acknowledged=false` | Unacknowledged only |
| `POST` | `/api/hr/lifecycle/alerts/{id}/acknowledge` | Mark alert acknowledged |
| `POST` | `/api/hr/lifecycle/alerts/process` | Manually trigger CRON job (admin use) |
| `GET` | `/api/hr/lifecycle/config?paysId=179&contractTypeCode=CDD` | Get config for one type |
| `PATCH` | `/api/hr/lifecycle/config/{id}` | Update config (alert days, trial days, etc.) |

---

## 9. Backend — Unit Tests

**File:** `src/test/java/com/daf360/rh/lifecycle/EmployeeLifecycleServiceTest.java`

16 pure Mockito unit tests (`@ExtendWith(MockitoExtension.class)`), no Spring context loaded.

### Test list

| # | Test | Validates |
|---|------|-----------|
| 1 | `createCDI_validData_setsRecrutementStatus` | CDI created → `PERIODE_ESSAI` status, trial date = start + 90 days |
| 2 | `createCDI_manager_setsLongerTrialPeriod` | Manager flag → trial = start + 180 days |
| 3 | `createCDD_validData_setsRecrutementStatus` | CDD created → `RECRUTEMENT` status |
| 4 | `createCDD_missingEndDate_throwsException` | CDD without `dateFinPrevue` → `BusinessRuleException` |
| 5 | `createContract_configNotFound_throwsException` | Missing `ContractTypeConfig` → `BusinessRuleException` |
| 6 | `transitionState_validTransition_updatesStatus` | Valid CDI RECRUTEMENT→PERIODE_ESSAI transition applies |
| 7 | `transitionState_invalidTransition_throwsException` | Invalid transition → `BusinessRuleException` |
| 8 | `transitionState_lockedDossier_throwsException` | `dossierLocked=true` → `BusinessRuleException` |
| 9 | `transitionState_terminalStatus_locksAndDeactivates` | FIN_CONTRAT → `dossierLocked=true`, `isActive=false` |
| 10 | `validateTrial_approved_setsActifConfirme` | `approved=true` → `ACTIF_CONFIRME` |
| 11 | `validateTrial_rejected_setsSuspended` | `approved=false` → `RESILIE`, `dossierLocked=true` |
| 12 | `renewCDD_validData_updatesEndDate` | CDD renewal → new `dateFinPrevue`, count incremented |
| 13 | `convertToCDI_createsNewCDIContract` | New CDI created, parent CDD closed with `CONVERSION_CDI` |
| 14 | `getAlerts_filtersByPaysAndAcknowledged` | Alert list filtered correctly |
| 15 | `acknowledgeAlert_setsAcknowledgedFlags` | Alert marked acknowledged with actor and timestamp |
| 16 | `sendAlert_simultaneousRHITDirector` | CRON sends in-app + email to users with RH/IT/DIRECTEUR_PAYS permissions |

### Run tests

```bash
cd rh-service
./mvnw test -pl . -Dtest=EmployeeLifecycleServiceTest
```

Expected output:
```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

---

## 10. Frontend — TypeScript Models

**File:** `src/app/modules/profiles/lifecycle/contract-lifecycle.model.ts`

### Status display config

```typescript
export const STATUS_CONFIG: Record<ContractStatus, StatusConfig> = {
  DRAFT:          { label: 'Brouillon',        bg: '#f1f5f9', color: '#475569' },
  ACTIF:          { label: 'Actif',             bg: '#d1fae5', color: '#065f46' },
  PERIODE_ESSAI:  { label: "Période d'essai",   bg: '#fef9c3', color: '#713f12' },
  ACTIF_CONFIRME: { label: 'Confirmé',          bg: '#d1fae5', color: '#065f46' },
  EXPIRE:         { label: 'Expiré',            bg: '#fee2e2', color: '#991b1b' },
  RESILIE:        { label: 'Résilié',           bg: '#fee2e2', color: '#991b1b' },
  CONVERTI:       { label: 'Converti en CDI',   bg: '#e0e7ff', color: '#3730a3' },
  RENOUVELE:      { label: 'Renouvelé',         bg: '#dbeafe', color: '#1e40af' },
  INACTIF:        { label: 'Inactif',           bg: '#f1f5f9', color: '#94a3b8' },
};
```

### Contract type config

```typescript
export const CONTRACT_TYPE_CONFIG: Record<ContractTypeCode, ContractTypeConfig> = {
  CDI:         { label: 'CDI',         needsEndDate: false, hasTrial: true  },
  CDD:         { label: 'CDD',         needsEndDate: true,  hasTrial: false },
  CIVP:        { label: 'CIVP',        needsEndDate: true,  hasTrial: false },
  STAGE:       { label: 'Stage',       needsEndDate: true,  hasTrial: false },
  DETACHEMENT: { label: 'Détachement', needsEndDate: true,  hasTrial: false },
  PORTAGE:     { label: 'Portage',     needsEndDate: false, hasTrial: false },
};
```

`needsEndDate` drives whether the "Date de fin prévue" field is shown/required in the new-contract form.

---

## 11. Frontend — HTTP Service

**File:** `src/app/modules/profiles/lifecycle/contract-lifecycle.service.ts`

Named `ContractLifecycleService` to avoid conflict with the existing `LifecycleService` (which manages workflow instances, not contracts).

```typescript
@Injectable({ providedIn: 'root' })
export class ContractLifecycleService {
  // Employee contracts
  getContracts(profileId: number): Observable<ContractListDto[]>
  createContract(profileId: number, req: CreateContractRequest): Observable<ContractDetailDto>
  getLifecycleHistory(profileId: number): Observable<ContractTransitionHistoryDto[]>

  // Single contract
  getContract(contractId: number): Observable<ContractDetailDto>
  transition(contractId: number, req: TransitionRequest): Observable<ContractDetailDto>
  validateTrial(contractId: number, req: ValidateTrialRequest): Observable<ContractDetailDto>
  renewCDD(contractId: number, req: RenewCDDRequest): Observable<ContractDetailDto>
  convertToCDI(contractId: number, req: ConvertToCDIRequest): Observable<ContractDetailDto>
  getContractHistory(contractId: number): Observable<ContractTransitionHistoryDto[]>

  // Alerts
  getAlerts(paysId: number, acknowledged?: boolean): Observable<LifecycleAlertDto[]>
  acknowledgeAlert(alertId: number): Observable<LifecycleAlertDto>
}
```

---

## 12. Frontend — New Contract Form

**File:** `src/app/modules/profiles/lifecycle/new-contract-form.component.ts`

Standalone modal component. Triggered from `profile-detail` via `showNewContractModal` signal.

**Inputs:**
- `profileId: number` (required)
- `paysId: number` (required)

**Outputs:**
- `saved: EventEmitter<ContractDetailDto>` — emitted on successful creation
- `cancelled: EventEmitter<void>` — emitted on cancel / modal close

**Usage in profile-detail.component.html:**
```html
@if (showNewContractModal()) {
  <app-new-contract-form
    [profileId]="profileId"
    [paysId]="profile()!.paysId"
    (saved)="onContractCreated($event)"
    (cancelled)="showNewContractModal.set(false)"
  />
}
```

**Form fields by type:**

| Contract type | Required fields | Optional / conditional fields |
|---------------|----------------|-------------------------------|
| CDI | Date début | Référence, checkbox Profil encadrant |
| CDD | Date début, Date fin | Référence |
| CIVP | Date début, Date fin | Référence ANETI, Date convention |
| STAGE | Date début, Date fin | École, checkbox Convention signée |
| DETACHEMENT | Date début, Date fin | Date retour prévu |
| PORTAGE | Date début | Référence |

---

## 13. Frontend — Profile Detail Integration

**File:** `src/app/modules/profiles/profile-detail.component.ts`

### Changes made

1. **`SectionKey` type extended:**
```typescript
type SectionKey =
  'identite' | 'emploi' | 'poste' | 'regime' | 'contact'
  | 'urgence' | 'bancaire' | 'lifecycle' | 'contrats' | 'documents';
```

2. **New signals added:**
```typescript
lcContracts    = signal<ContractListDto[]>([]);
lcLoading      = signal(false);
lcLoaded       = false;                        // load-once guard
lcHistory      = signal<ContractTransitionHistoryDto[]>([]);
lcSaving       = signal(false);
lcError        = signal<string | null>(null);

showNewContractModal   = signal(false);
showValidateTrialModal = signal(false);
showRenewCDDModal      = signal(false);
showConvertCDIModal    = signal(false);

selectedContractId: number | null = null;
```

3. **Helper methods for template type safety:**
```typescript
// Avoids TS7053 — Record<ContractStatus,…> can't be indexed by plain string
lcStatusCfg(code: string) {
  return this.statusCfg[code as keyof typeof STATUS_CONFIG]
    ?? { label: code, bg: '#f1f5f9', color: '#475569' };
}
lcTypeCfg(code: string) {
  return this.typeCfg[code as keyof typeof CONTRACT_TYPE_CONFIG]
    ?? { label: code, needsEndDate: false, hasTrial: false };
}
```

4. **`loadContracts()` — load-once pattern:**
```typescript
loadContracts(): void {
  if (this.lcLoaded) return;    // skip if already loaded
  this.lcLoaded = true;
  this.lcSvc.getContracts(this.profileId).subscribe(cs => this.lcContracts.set(cs));
  this.lcSvc.getLifecycleHistory(this.profileId).subscribe(h => this.lcHistory.set(h));
}
```
Called from the collapsible section header `(click)` binding so data is only fetched when the section is first opened.

### Collapsible section in profile-detail.component.html

The section is inserted between "Coordonnées bancaires" and the save bar (line ~543 of the original file):

```html
<!-- Contrats & Cycle de vie -->
<section class="section-card" [class.collapsed]="!open('lifecycle')">
  <button class="section-header" (click)="toggle('lifecycle'); loadContracts()" type="button">
    <span class="section-title">
      Contrats &amp; Cycle de vie
      @if (lcContracts().length > 0) {
        <span class="count-badge">{{ lcContracts().length }}</span>
      }
    </span>
    <span class="chevron" [class.open]="open('lifecycle')">›</span>
  </button>
  @if (open('lifecycle')) {
    <div class="section-body">
      <!-- Spinner / contract cards / history timeline -->
    </div>
  }
</section>
```

### Per-contract action buttons (visible to HR managers only)

| Condition | Button shown |
|-----------|-------------|
| `currentStatusCode === 'PERIODE_ESSAI'` | **Valider période d'essai** |
| `contractTypeCode === 'CDD' && currentStatusCode === 'ACTIF'` | **Renouveler CDD** |
| `contractTypeCode === 'CDD' && currentStatusCode === 'ACTIF'` | **Convertir en CDI** |

All buttons are hidden if `dossierLocked === true`.

### Inline modals

Three modals added inline in `profile-detail.component.html` (using the shared `<app-modal>` component):
- **Validate trial** — radio: approve (→ `ACTIF_CONFIRME`) / reject (→ `RESILIE`) + comment textarea
- **Renew CDD** — new end date (required) + comment
- **Convert to CDI** — CDI start date (required) + comment

---

## 14. Frontend — Sidebar Alert Badge

### hr-shell.component.ts

```typescript
lifecycleAlertCount = signal(0);

ngOnInit(): void {
  // ... existing onboarding count ...

  const paysId = this.userStore.currentUser()?.paysId;
  if (paysId && this.userStore.hasPermission('RH_VIEW_CONTRACTS')) {
    this.http.get<any[]>(
      `${environment.hrApiUrl}/api/hr/lifecycle/alerts?paysId=${paysId}&acknowledged=false`
    ).subscribe({
      next: list => this.lifecycleAlertCount.set(list.length),
      error: () => {},
    });
  }
}
```

### hr-shell.component.html

```html
@if (item.path === '/hr/lifecycle' && lifecycleAlertCount() > 0) {
  <span class="nav-count-badge">{{ lifecycleAlertCount() }}</span>
}
```

The badge appears next to the **Lifecycle** nav item in the sidebar. It only loads if the current user has the `RH_VIEW_CONTRACTS` permission and their `paysId` is set.

---

## 15. User Guide

### How to create a new contract (HR Manager)

1. Open any employee profile: **Profils** → click on an employee row
2. Scroll to the **"Contrats & Cycle de vie"** section and click to expand it
3. Click **"+ Nouveau contrat"**
4. Select the contract type (CDI, CDD, CIVP, Stage, Détachement, or Portage)
5. Fill in the required dates — "Date de fin prévue" appears automatically for types that require it
6. For CDI: check **"Profil encadrant"** if the employee will have managerial responsibilities (extends trial period from 3 months to 6 months)
7. Click **"Créer le contrat"**

The contract appears in the list immediately. For CDI, the status will be **Période d'essai** with the calculated end date displayed.

---

### How to validate a trial period (CDI)

1. Expand **"Contrats & Cycle de vie"** on the employee's profile
2. Find the active contract with the **"Période d'essai"** badge
3. Click **"Valider période d'essai"**
4. Choose:
   - **Approuver** → contract moves to **Confirmé** (`ACTIF_CONFIRME`)
   - **Refuser** → contract moves to **Résilié** (`RESILIE`) and is permanently locked
5. Add an optional comment
6. Click **"Confirmer"**

---

### How to renew a CDD

1. Find the active CDD contract (status **Actif**)
2. Click **"Renouveler CDD"**
3. Enter the new end date
4. Click **"Renouveler"**

The renewal counter (`cdd_renouvellement_count`) increments automatically.

---

### How to convert a CDD to CDI

1. Find the active CDD contract (status **Actif**)
2. Click **"Convertir en CDI"**
3. Enter the CDI start date
4. Click **"Convertir"**

This creates a new CDI contract in **Période d'essai** status. The original CDD is closed with status **Converti en CDI** and locked.

---

### Lifecycle alerts in the sidebar

The **Lifecycle** item in the left sidebar shows a count badge for unacknowledged alerts. These are contracts expiring within the next 30 days for your country. The daily CRON job (08:00) sends in-app notifications and emails to all users with the `RH_VIEW_CONTRACTS`, `RH_MANAGE_LIFECYCLE`, or `RH_APPROVE_RECRUITMENT_DEMAND` permissions.

To acknowledge an alert, use:
```
POST /api/hr/lifecycle/alerts/{id}/acknowledge
```

---

### Manually trigger the alert job (admin)

```bash
curl -X POST http://localhost:8082/api/hr/lifecycle/alerts/process \
  -H "Authorization: Bearer <JWT>"
```

Returns `202 Accepted`.

---

## 16. Pending Work

### Critical — must do before first deployment

| Item | Notes |
|------|-------|
| Create `V33__employee_lifecycle_engine.sql` | Use the SQL in Section 3 of this document |
| Verify `employee_contracts.employee_profile_id` FK exists | References `employee_profiles(id)` |
| Add `RH_VIEW_CONTRACTS` and `RH_MANAGE_LIFECYCLE` permissions to `RolePermissions` seed | Required for alert filtering and permission checks |

### Nice to have

| Item | Notes |
|------|-------|
| Acknowledge alert button in profile-detail UI | Currently only available via direct API call |
| Alerts list page at `/hr/lifecycle` | Currently the route goes to the workflow lifecycle, not contract alerts |
| Contract detail drawer/modal in profile | Clicking on a contract could show all fields (CIVP reference, STAGE school, etc.) |
| CDD max renewal guard | Check `cdd_renouvellement_count` against per-country config limit |
| `@PreAuthorize` on service methods | Controller currently relies on JWT but service methods lack method-level security |
| CIVP age validation | Backend should validate candidate age ≤ `civp_max_age` from config |

---

*Document generated 2026-06-18 — Employee Lifecycle Engine v1.0*
