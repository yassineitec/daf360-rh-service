-- ============================================================
-- V44 — Offboarding Workflow (replaces conflicting V38)
-- Delete V38__offboarding_workflow.sql after applying this.
-- Created: 2026-07-15
-- ============================================================

-- ── Table 1: offboarding_task_catalog ───────────────────────
CREATE TABLE [dbo].[offboarding_task_catalog] (
  [id]               BIGINT IDENTITY(1,1) NOT NULL,
  [pays_id]          BIGINT               NOT NULL,
  [contract_type]    NVARCHAR(50)         NOT NULL,
  [task_code]        NVARCHAR(50)         NOT NULL,
  [task_label]       NVARCHAR(255)        NOT NULL,
  [owner_role]       NVARCHAR(100)        NOT NULL,
  [is_mandatory]     BIT                  NOT NULL DEFAULT 1,
  [is_blocking]      BIT                  NOT NULL DEFAULT 0,
  [sla_working_days] INT                  NOT NULL DEFAULT 5,
  [order_index]      INT                  NOT NULL DEFAULT 0,
  [is_active]        BIT                  NOT NULL DEFAULT 1,
  [created_at]       DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
  CONSTRAINT [PK_offboarding_task_catalog] PRIMARY KEY ([id]),
  CONSTRAINT [UX_task_catalog] UNIQUE ([pays_id], [contract_type], [task_code])
);
CREATE NONCLUSTERED INDEX [IX_task_catalog_pays_type] ON [dbo].[offboarding_task_catalog]([pays_id], [contract_type]);

-- ── Table 2: offboarding_workflow_instances ─────────────────
CREATE TABLE [dbo].[offboarding_workflow_instances] (
  [id]                  BIGINT IDENTITY(1,1) NOT NULL,
  [pays_id]             BIGINT               NOT NULL,
  [employee_profile_id] BIGINT               NOT NULL,
  [contract_id]         BIGINT               NULL,
  [trigger_date]        DATE                 NOT NULL,
  [last_working_day]    DATE                 NULL,
  [departure_reason]    NVARCHAR(100)        NOT NULL,
  [departure_notes]     NVARCHAR(1000)       NULL,
  [status]              NVARCHAR(30)         NOT NULL DEFAULT 'PENDING',
  [initiated_by]        BIGINT               NOT NULL,
  [validated_by]        BIGINT               NULL,
  [validated_at]        DATETIMEOFFSET(6)    NULL,
  [cancelled_by]        BIGINT               NULL,
  [cancelled_at]        DATETIMEOFFSET(6)    NULL,
  [cancellation_reason] NVARCHAR(500)        NULL,
  [sla_breach_flag]     BIT                  NOT NULL DEFAULT 0,
  [completion_date]     DATETIMEOFFSET(6)    NULL,
  [created_at]          DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
  [updated_at]          DATETIMEOFFSET(6)    NULL,
  CONSTRAINT [PK_offboarding_workflow_instances] PRIMARY KEY ([id]),
  CONSTRAINT [CK_offboarding_status] CHECK ([status] IN ('PENDING','IN_PROGRESS','BLOCKED','VALIDATED','CANCELLED','ARCHIVED')),
  CONSTRAINT [CK_offboarding_reason] CHECK ([departure_reason] IN ('RESIGNATION','FIN_CONTRAT','LICENCIEMENT','RETRAITE','FIN_STAGE','FIN_MISSION','AUTRE'))
);
CREATE NONCLUSTERED INDEX [IX_offboarding_profile] ON [dbo].[offboarding_workflow_instances]([employee_profile_id]);
CREATE NONCLUSTERED INDEX [IX_offboarding_pays_status] ON [dbo].[offboarding_workflow_instances]([pays_id],[status]);

-- ── Table 3: offboarding_tasks ──────────────────────────────
CREATE TABLE [dbo].[offboarding_tasks] (
  [id]                    BIGINT IDENTITY(1,1) NOT NULL,
  [workflow_instance_id]  BIGINT               NOT NULL,
  [task_code]             NVARCHAR(50)         NOT NULL,
  [task_label]            NVARCHAR(255)        NOT NULL,
  [owner_role]            NVARCHAR(100)        NOT NULL,
  [owner_user_id]         BIGINT               NULL,
  [is_mandatory]          BIT                  NOT NULL DEFAULT 1,
  [is_blocking]           BIT                  NOT NULL DEFAULT 0,
  [due_date]              DATE                 NOT NULL,
  [status]                NVARCHAR(20)         NOT NULL DEFAULT 'PENDING',
  [completed_by]          BIGINT               NULL,
  [completed_at]          DATETIMEOFFSET(6)    NULL,
  [skipped_by]            BIGINT               NULL,
  [skip_reason]           NVARCHAR(500)        NULL,
  [comments]              NVARCHAR(2000)       NULL,
  [attached_document_url] NVARCHAR(500)        NULL,
  [sla_breach_date]       DATETIMEOFFSET(6)    NULL,
  [created_at]            DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
  CONSTRAINT [PK_offboarding_tasks] PRIMARY KEY ([id]),
  CONSTRAINT [FK_tasks_workflow] FOREIGN KEY ([workflow_instance_id]) REFERENCES [dbo].[offboarding_workflow_instances]([id]),
  CONSTRAINT [CK_task_status] CHECK ([status] IN ('PENDING','IN_PROGRESS','DONE','BLOCKED','SKIPPED'))
);
CREATE NONCLUSTERED INDEX [IX_tasks_workflow] ON [dbo].[offboarding_tasks]([workflow_instance_id]);
CREATE NONCLUSTERED INDEX [IX_tasks_owner] ON [dbo].[offboarding_tasks]([owner_user_id]);

-- ── Table 4: offboarding_asset_returns ─────────────────────
CREATE TABLE [dbo].[offboarding_asset_returns] (
  [id]                    BIGINT IDENTITY(1,1) NOT NULL,
  [workflow_instance_id]  BIGINT               NOT NULL,
  [task_id]               BIGINT               NULL,
  [asset_description]     NVARCHAR(255)        NOT NULL,
  [asset_type]            NVARCHAR(50)         NOT NULL DEFAULT 'IT',
  [expected_return_date]  DATE                 NOT NULL,
  [actual_return_date]    DATE                 NULL,
  [condition_on_return]   NVARCHAR(100)        NULL,
  [confirmed_by]          BIGINT               NULL,
  [confirmed_at]          DATETIMEOFFSET(6)    NULL,
  [is_written_off]        BIT                  NOT NULL DEFAULT 0,
  [write_off_approved_by] BIGINT               NULL,
  [write_off_reason]      NVARCHAR(500)        NULL,
  [created_at]            DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
  CONSTRAINT [PK_asset_returns] PRIMARY KEY ([id]),
  CONSTRAINT [FK_asset_workflow] FOREIGN KEY ([workflow_instance_id]) REFERENCES [dbo].[offboarding_workflow_instances]([id])
);

-- ── Table 5: exit_interviews ────────────────────────────────
CREATE TABLE [dbo].[exit_interviews] (
  [id]                    BIGINT IDENTITY(1,1) NOT NULL,
  [workflow_instance_id]  BIGINT               NOT NULL,
  [conducted_by]          BIGINT               NOT NULL,
  [conducted_date]        DATE                 NOT NULL,
  [departure_reasons]     NVARCHAR(1000)       NULL,
  [feedback_text]         NVARCHAR(4000)       NULL,
  [is_anonymised]         BIT                  NOT NULL DEFAULT 0,
  [anonymised_at]         DATETIMEOFFSET(6)    NULL,
  [visible_to_roles]      NVARCHAR(500)        NULL,
  [created_at]            DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
  [updated_at]            DATETIMEOFFSET(6)    NULL,
  CONSTRAINT [PK_exit_interviews] PRIMARY KEY ([id]),
  CONSTRAINT [FK_exit_workflow] FOREIGN KEY ([workflow_instance_id]) REFERENCES [dbo].[offboarding_workflow_instances]([id]),
  CONSTRAINT [UX_exit_workflow] UNIQUE ([workflow_instance_id])
);

-- ── Permissions seed ────────────────────────────────────────
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, p.permission
FROM [dbo].[Roles] r
CROSS JOIN (VALUES
  ('RH_MANAGE_OFFBOARDING'),
  ('RH_COMPLETE_OFFBOARDING_TASK'),
  ('RH_VALIDATE_OFFBOARDING'),
  ('RH_CONDUCT_EXIT_INTERVIEW'),
  ('RH_SUSPEND_PROFILE')
) p(permission)
WHERE r.frenchName IN (
  'Directeur des Ressources Humaines (DRH)',
  'Administrateur'
)
AND NOT EXISTS (
  SELECT 1 FROM [dbo].[RolePermissions] rp
  WHERE rp.role_id = r.id AND rp.permission = p.permission
);

-- ── Task catalog seed ───────────────────────────────────────
INSERT INTO [dbo].[offboarding_task_catalog]
  (pays_id, contract_type, task_code, task_label, owner_role, is_mandatory, is_blocking, sla_working_days, order_index)
SELECT p.id, ct.contract_type, tk.task_code, tk.task_label,
       tk.owner_role, tk.is_mandatory, tk.is_blocking, tk.sla_days, tk.ord
FROM [dbo].[pays] p
CROSS JOIN (VALUES ('CDI'),('CDD'),('STAGE'),('FREELANCE'),('CIVP')) ct(contract_type)
CROSS JOIN (VALUES
  ('ASSET_RETURN_IT',        N'Retour des équipements IT',             'IT_OFFICER',         1, 1, 3,  1),
  ('ASSET_RETURN_BADGE',     N'Retour badge et accès locaux',          'FACILITIES_OFFICER', 1, 0, 3,  2),
  ('KNOWLEDGE_TRANSFER',     N'Passation des projets et dossiers',     'HOME_BASE_MANAGER',  1, 0, 5,  3),
  ('EXIT_INTERVIEW',         N'Entretien de sortie',                   'HR_OFFICER',         1, 0, 3,  4),
  ('FINAL_SETTLEMENT',       N'Solde de tout compte',                  'FINANCE_OFFICER',    1, 1, 15, 5),
  ('IT_ACCESS_REVOKE',       N'Désactivation des accès informatiques', 'IT_OFFICER',         1, 0, 1,  6),
  ('WORK_CERTIFICATE',       N'Remise de l''attestation de travail',   'HR_OFFICER',         1, 0, 5,  7),
  ('EXPENSE_CLOSE',          N'Clôture des notes de frais en suspens', 'FINANCE_OFFICER',    0, 0, 5,  8),
  ('INTERNAL_ANNOUNCEMENT',  N'Annonce interne du départ',             'HR_OFFICER',         0, 0, 2,  9)
) tk(task_code, task_label, owner_role, is_mandatory, is_blocking, sla_days, ord)
WHERE NOT EXISTS (
  SELECT 1 FROM [dbo].[offboarding_task_catalog] c
  WHERE c.pays_id = p.id AND c.contract_type = ct.contract_type AND c.task_code = tk.task_code
);
GO
