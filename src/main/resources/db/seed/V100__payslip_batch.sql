-- V100 — Monthly payslip batch: split-by-page + upload to SharePoint.
--
-- Adds employee_profiles.payroll_matricule: the EXTERNAL payroll software's own employee
-- number (printed as "Matricule" on its payslip PDFs), used to match each page of a monthly
-- batch PDF to the right employee. This is DELIBERATELY a new, separate column from the
-- existing Users.employee_id (this app's own auto-generated "matricule", format
-- [NOM3][PRE3][userId] via EmployeeIdGeneratorService) — the two are unrelated numbering
-- systems (the payroll sample seen so far uses a bare small integer like "208", nothing like
-- the app's own generated codes), and conflating them would either break the existing
-- generator or silently mismatch every payslip.
--
-- Nullable, unpopulated by this migration: no source of truth for the real values exists yet
-- (HR will need to fill them in, e.g. via the admin profile screen, before the first real
-- batch can fully match). Unique per pays_id, not globally, since Tunisia and Egypt run
-- independent payroll systems that could both assign the same small number to different
-- people.
--
-- Also grants the new RH_MANAGE_PAYSLIPS permission (see PermissionCatalog.java) to the same
-- roles that already hold HR_UPDATE_PROFILE — this feature touches every employee's salary
-- document in one action, so it gets at least that level of access control from day one —
-- PLUS "Directeur Administratif et Financier (DAF)" explicitly: the upload screen itself
-- lives in the Finance app (daf360-facturation-frontend, under Couts), not the HR app, and
-- DAF is the real role finance's other RH-reaching cost screens are granted under
-- (FACT_ADMIN_COST) — without this row, the one role finance actually uses would be the one
-- role locked out of the screen placed in its own app.

USE [DAF360_HR];
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'payroll_matricule')
    ALTER TABLE [dbo].[employee_profiles]
        ADD [payroll_matricule] NVARCHAR(50) NULL;
GO

-- Filtered indexes require QUOTED_IDENTIFIER ON for the session that creates them (a SQL
-- Server requirement, unrelated to this table) -- sqlcmd does not always set it by default.
-- NOTE this also applies to the app's own JDBC connection at runtime and to any later hand-run
-- UPDATE/INSERT against employee_profiles from sqlcmd -- both failed with error 1934 during
-- local verification of this migration until QUOTED_IDENTIFIER ON was set first. The JDBC
-- driver sets it ON by default, so the running application itself is unaffected; this only
-- bites ad-hoc sqlcmd sessions.
SET QUOTED_IDENTIFIER ON;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_EmployeeProfile_PaysId_PayrollMatricule')
    CREATE UNIQUE NONCLUSTERED INDEX [UX_EmployeeProfile_PaysId_PayrollMatricule]
        ON [dbo].[employee_profiles]([pays_id], [payroll_matricule])
        WHERE [payroll_matricule] IS NOT NULL;
GO

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT DISTINCT rp.role_id, 'RH_MANAGE_PAYSLIPS'
FROM [dbo].[RolePermissions] rp
WHERE rp.permission = 'HR_UPDATE_PROFILE'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] existing
      WHERE existing.role_id = rp.role_id AND existing.permission = 'RH_MANAGE_PAYSLIPS'
  );
GO

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_MANAGE_PAYSLIPS'
FROM [dbo].[Roles] r
WHERE r.frenchName = N'Directeur Administratif et Financier (DAF)'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] existing
      WHERE existing.role_id = r.id AND existing.permission = 'RH_MANAGE_PAYSLIPS'
  );
GO
