-- ============================================================
-- V60 — Offboarding checklists + PV de passation
--
-- ⚠️ The module 500s until this is applied. rh-service runs ddl-auto: none, and
-- OffboardingWorkflowInstance now maps `handover_minutes_url`, so every SELECT on the
-- table names a column SQL Server does not have yet. Apply before deploying the service.
--
-- ONE table serves three lists — the handover checklist (stage 3), the access revocation
-- list (stage 4) and the Kit RH (stage 5). Chosen over adding ~6 more
-- `offboarding_task_catalog` codes: those would inherit SLA / owner / blocking semantics
-- they do not want, would push the task list from 9 to ~15 rows, and would make
-- `computeProgress` count document handover as workflow progress.
--
-- HANDOVER items are created ad hoc (each departure hands over different work), so none
-- are seeded. ACCESS and KIT are fixed lists and ARE seeded per instance.
--
--   sqlcmd -S <server> -d DAF360_HR -i V60__offboarding_checklist_items.sql
-- Re-runnable: guarded columns, guarded table, guarded seeds.
-- Created: 2026-08-04
-- ============================================================

-- ── PV de passation on the instance ──────────────────────────
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'handover_minutes_url') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [handover_minutes_url] NVARCHAR(500) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'handover_minutes_name') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [handover_minutes_name] NVARCHAR(255) NULL;
GO

-- ── The checklist table ──────────────────────────────────────
IF OBJECT_ID('dbo.offboarding_checklist_items', 'U') IS NULL
BEGIN
  CREATE TABLE [dbo].[offboarding_checklist_items] (
    [id]                   BIGINT IDENTITY(1,1) NOT NULL,
    [workflow_instance_id] BIGINT               NOT NULL,
    [group_code]           NVARCHAR(20)         NOT NULL,   -- HANDOVER | ACCESS | KIT
    [item_code]            NVARCHAR(50)         NOT NULL,
    [item_label]           NVARCHAR(255)        NOT NULL,
    [is_done]              BIT                  NOT NULL DEFAULT 0,
    [document_url]         NVARCHAR(500)        NULL,
    [completed_by]         BIGINT               NULL,
    [completed_at]         DATETIMEOFFSET(6)    NULL,
    [order_index]          INT                  NOT NULL DEFAULT 0,
    [created_at]           DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT [PK_offboarding_checklist_items] PRIMARY KEY ([id]),
    CONSTRAINT [FK_checklist_workflow] FOREIGN KEY ([workflow_instance_id])
      REFERENCES [dbo].[offboarding_workflow_instances]([id]),
    CONSTRAINT [UX_checklist_item] UNIQUE ([workflow_instance_id], [group_code], [item_code]),
    CONSTRAINT [CK_checklist_group] CHECK ([group_code] IN ('HANDOVER','ACCESS','KIT'))
  );
  CREATE NONCLUSTERED INDEX [IX_checklist_workflow]
    ON [dbo].[offboarding_checklist_items]([workflow_instance_id], [group_code]);
  PRINT 'offboarding_checklist_items created.';
END
ELSE
  PRINT 'offboarding_checklist_items already present.';
GO

-- ── Backfill: give files already in flight the same lists new ones get ──
--
-- Without this, an existing file shows empty ACCESS and KIT lists while a file opened
-- five minutes later shows six rows — the same stage behaving differently for no reason
-- the user can see. Terminal files are left alone: adding unticked obligations to a
-- closed departure would misreport it as incomplete.
INSERT INTO [dbo].[offboarding_checklist_items]
  (workflow_instance_id, group_code, item_code, item_label, order_index)
SELECT w.id, s.group_code, s.item_code, s.item_label, s.order_index
FROM [dbo].[offboarding_workflow_instances] w
CROSS JOIN (VALUES
  ('ACCESS', 'WORKSPACE_CLOSE',    N'Fermeture Workspace / Microsoft 365',      1),
  ('ACCESS', 'VPN_ERP_REVOKE',     N'Révocation des accès VPN / ERP',           2),
  ('ACCESS', 'MAIL_REDIRECT',      N'Redirection des emails vers le manager',   3),
  ('KIT',    'WORK_CERTIFICATE',   N'Certificat de travail',                    1),
  ('KIT',    'END_OF_CONTRACT',    N'Attestation de fin de contrat',            2),
  ('KIT',    'SETTLEMENT_RECEIPT', N'Reçu pour solde de tout compte',           3)
) AS s(group_code, item_code, item_label, order_index)
WHERE w.status IN ('PENDING','IN_PROGRESS','BLOCKED')
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[offboarding_checklist_items] c
    WHERE c.workflow_instance_id = w.id
      AND c.group_code = s.group_code
      AND c.item_code  = s.item_code
  );

PRINT 'V60 — ' + CAST(@@ROWCOUNT AS VARCHAR) + ' checklist item(s) backfilled onto active instances.';
GO
