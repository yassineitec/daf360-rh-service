-- V52__hiring_cost_approval_permissions.sql
-- Grants hiring cost simulation/approval permissions to Administrateur.
-- RH_HIRE_CANDIDATE  — submit a simulation for Directeur Pays review
-- APPROVE_HIRING_COST — CD approval / rejection of submitted simulations

DECLARE @codes TABLE (permission NVARCHAR(255));
INSERT INTO @codes VALUES
    ('RH_HIRE_CANDIDATE'),
    ('APPROVE_HIRING_COST');

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
