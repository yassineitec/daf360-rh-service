-- ============================================================
-- V66 — Who may give the RH validation of an offboarding, per pays
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped table).
--
-- WHY A TABLE AND NOT A PERMISSION: stage 2's RH validation is gated on
-- `RH_VALIDATE_OFFBOARDING`, which V44 grants to DRH + Administrateur GLOBALLY. A permission
-- belongs to a role, and a role has no country — so "the country director validates the
-- departures of their own country" cannot be expressed as one. `findInstanceOrThrow`'s tenant
-- check does not help either: `getEffectivePaysId()` is null exactly for the admin and
-- show-all roles that hold this permission today.
--
-- The role is chosen freely from `Roles` rather than hardcoded ("Directeur de pays"): role
-- naming is per-deployment, and pinning a label here would break the first time someone
-- renames one.
--
-- NOT derived from `Roles.parent_role_id`: that would make the validator implicit in the
-- hierarchy, which has to be accurate for every employee for the answer to be right.
--
-- FALLBACK: a pays with no row keeps the current behaviour (any RH_VALIDATE_OFFBOARDING or
-- RH_MANAGE_OFFBOARDING holder). Deliberate — the alternative is that applying this migration
-- freezes stage 2 for every country until someone configures it.
--
-- No seed: nothing is restricted until a row is created, which is what makes this safe to
-- apply ahead of the configuration.
--
--   sqlcmd -S <server> -d DAF360_HR -i V66__offboarding_validators.sql
-- Re-runnable: guarded table.
-- Created: 2026-08-05
-- ============================================================

IF OBJECT_ID('dbo.offboarding_validators', 'U') IS NULL
BEGIN
  CREATE TABLE [dbo].[offboarding_validators] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]    BIGINT               NOT NULL,
    [role_id]    BIGINT               NOT NULL,
    [created_at] DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    [updated_at] DATETIMEOFFSET(6)    NULL,
    [updated_by] BIGINT               NULL,
    CONSTRAINT [PK_offboarding_validators] PRIMARY KEY ([id]),
    -- One validator role per country: two would reintroduce the ambiguity this table exists
    -- to remove. Widen to a set only if the business actually names several.
    CONSTRAINT [UQ_offboarding_validators_pays] UNIQUE ([pays_id]),
    CONSTRAINT [FK_offboarding_validators_pays] FOREIGN KEY ([pays_id])
      REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [FK_offboarding_validators_role] FOREIGN KEY ([role_id])
      REFERENCES [dbo].[Roles]([id])
  );
  PRINT 'offboarding_validators created.';
END
ELSE
  PRINT 'offboarding_validators already present.';
GO
