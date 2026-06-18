-- V33: Employee Lifecycle Engine tables
-- Creates employee_contracts, employee_lifecycle_transitions,
-- employee_lifecycle_alerts, contract_type_config.
-- Requires V32 (employee_profiles.lifecycle_status_code + current_contract_id already added).

-- ── employee_contracts ────────────────────────────────────────────────────────
CREATE TABLE [dbo].[employee_contracts] (
    id                          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    employee_profile_id         BIGINT NOT NULL,
    pays_id                     BIGINT NOT NULL,
    contract_type_code          VARCHAR(30) NOT NULL,
    current_status_code         VARCHAR(50) NOT NULL,

    date_debut                  DATE NOT NULL,
    date_fin_prevue             DATE NULL,
    date_fin_periode_essai      DATE NULL,
    date_fin_effective          DATE NULL,

    reference_contrat           NVARCHAR(100) NULL,
    is_active                   BIT NOT NULL DEFAULT 1,
    dossier_locked              BIT NOT NULL DEFAULT 0,
    manager_profile             BIT NOT NULL DEFAULT 0,
    cdd_renouvellement_count    INT NOT NULL DEFAULT 0,

    -- CIVP
    civp_aneti_reference        NVARCHAR(100) NULL,
    civp_convention_date        DATE NULL,

    -- STAGE
    stage_ecole                 NVARCHAR(200) NULL,
    stage_tuteur_id             BIGINT NULL,
    stage_convention_signee     BIT NOT NULL DEFAULT 0,

    -- PORTAGE / FREELANCE
    freelance_tjm               DECIMAL(10,2) NULL,
    freelance_devise            VARCHAR(10) NULL,
    freelance_societe           NVARCHAR(200) NULL,

    -- DETACHEMENT
    detachement_entite_origine_id  BIGINT NULL,
    detachement_entite_accueil_id  BIGINT NULL,
    detachement_retour_prevu       DATE NULL,

    -- CDD renewal parent
    cdd_contrat_parent_id       BIGINT NULL,

    created_by                  BIGINT NOT NULL,
    created_at                  DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),

    CONSTRAINT FK_EmployeeContract_Profile
        FOREIGN KEY (employee_profile_id) REFERENCES [dbo].[employee_profiles](id),

    CONSTRAINT FK_EmployeeContract_CddParent
        FOREIGN KEY (cdd_contrat_parent_id) REFERENCES [dbo].[employee_contracts](id)
);

CREATE INDEX IX_EmployeeContracts_ProfileId   ON [dbo].[employee_contracts] (employee_profile_id);
CREATE INDEX IX_EmployeeContracts_PaysId       ON [dbo].[employee_contracts] (pays_id);
CREATE INDEX IX_EmployeeContracts_IsActive     ON [dbo].[employee_contracts] (is_active);

-- ── employee_lifecycle_transitions ───────────────────────────────────────────
CREATE TABLE [dbo].[employee_lifecycle_transitions] (
    id              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    contract_id     BIGINT NOT NULL,
    statut_avant    VARCHAR(50) NULL,
    statut_apres    VARCHAR(50) NOT NULL,
    action_code     VARCHAR(50) NOT NULL,
    triggered_by    BIGINT NOT NULL,
    triggered_at    DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    commentaire     NVARCHAR(500) NULL,

    CONSTRAINT FK_LifecycleTransition_Contract
        FOREIGN KEY (contract_id) REFERENCES [dbo].[employee_contracts](id)
);

CREATE INDEX IX_LifecycleTransitions_ContractId ON [dbo].[employee_lifecycle_transitions] (contract_id);

-- ── employee_lifecycle_alerts ─────────────────────────────────────────────────
CREATE TABLE [dbo].[employee_lifecycle_alerts] (
    id               BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    contract_id      BIGINT NOT NULL,
    pays_id          BIGINT NOT NULL,
    alert_type       VARCHAR(50) NOT NULL,
    target_date      DATE NOT NULL,
    is_acknowledged  BIT NOT NULL DEFAULT 0,
    acknowledged_at  DATETIMEOFFSET(6) NULL,
    employee_name    NVARCHAR(200) NULL,
    created_at       DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),

    CONSTRAINT FK_LifecycleAlert_Contract
        FOREIGN KEY (contract_id) REFERENCES [dbo].[employee_contracts](id)
);

CREATE INDEX IX_LifecycleAlerts_PaysId         ON [dbo].[employee_lifecycle_alerts] (pays_id);
CREATE INDEX IX_LifecycleAlerts_IsAcknowledged ON [dbo].[employee_lifecycle_alerts] (is_acknowledged);

-- ── contract_type_config ──────────────────────────────────────────────────────
CREATE TABLE [dbo].[contract_type_config] (
    id                          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    pays_id                     BIGINT NOT NULL,
    contract_type_code          VARCHAR(30) NOT NULL,
    trial_period_days_standard  INT NOT NULL DEFAULT 90,
    trial_period_days_manager   INT NOT NULL DEFAULT 180,
    alert_days_before_expiry    INT NOT NULL DEFAULT 30,

    CONSTRAINT UQ_ContractTypeConfig_PaysType
        UNIQUE (pays_id, contract_type_code)
);

-- ── Seed: Tunisia (pays_id = 1) ───────────────────────────────────────────────
INSERT INTO [dbo].[contract_type_config]
    (pays_id, contract_type_code, trial_period_days_standard, trial_period_days_manager, alert_days_before_expiry)
VALUES
    (1, 'CDI',         90, 180, 30),
    (1, 'CDD',          0,   0, 30),
    (1, 'CIVP',         0,   0, 30),
    (1, 'STAGE',        0,   0, 30),
    (1, 'PORTAGE',      0,   0, 30),
    (1, 'DETACHEMENT',  0,   0, 30);

-- Add FK from employee_profiles to current_contract_id (if not already present from V32)
-- Safe: uses IF NOT EXISTS pattern
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'current_contract_id'
)
BEGIN
    ALTER TABLE [dbo].[employee_profiles]
        ADD current_contract_id BIGINT NULL,
            lifecycle_status_code VARCHAR(50) NULL;
END;
