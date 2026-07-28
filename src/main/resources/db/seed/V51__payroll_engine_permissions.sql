-- V51__payroll_engine_permissions.sql
-- Grants all PAYROLL_* permission codes to the Administrateur role.
-- New codes added: PAYROLL_RUN_ENGINE, PAYROLL_VIEW_RESULTS, PAYROLL_MANAGE_RUBRIQUES,
--   PAYROLL_MANAGE_COUNTRIES, PAYROLL_IMPORT_CALIBRATION (D3-234 to D3-258 universal engine).
-- Existing codes included for idempotency (safe on fresh DBs that never had them).

DECLARE @codes TABLE (permission NVARCHAR(255));
INSERT INTO @codes VALUES
    ('PAYROLL_RUN_SIMULATION'),
    ('PAYROLL_VIEW_INDIVIDUAL'),
    ('PAYROLL_APPROVE_PARAMSET'),
    ('PAYROLL_RUN_CALIBRATION'),
    ('PAYROLL_EXPORT_BUDGET'),
    ('PAYROLL_IMPORT_PARTNER'),
    ('PAYROLL_VIEW_AGGREGATE'),
    ('PAYROLL_APPROVE_PARAMSET_FAST_TRACK'),
    ('PAYROLL_VIEW_PARAMSET'),
    ('PAYROLL_UPLOAD_ACTUAL'),
    ('PAYROLL_SUPER_ADMIN'),
    ('PAYROLL_RUN_ENGINE'),
    ('PAYROLL_VIEW_RESULTS'),
    ('PAYROLL_MANAGE_RUBRIQUES'),
    ('PAYROLL_MANAGE_COUNTRIES'),
    ('PAYROLL_IMPORT_CALIBRATION');

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, c.permission
FROM [dbo].[Roles] r
CROSS JOIN @codes c
WHERE r.frenchName = N'Administrateur'
  AND r.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id AND rp.permission = c.permission
  );
GO
