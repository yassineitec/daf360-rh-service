-- V79__mission_permissions.sql
-- The three codes of the mission process, granted to Administrateur.
--
--   RH_CREATE_MISSION              — a manager plans missions for their own team.
--                                    Being a non-prefixed (RH) code, it is also what
--                                    makes the "Ressources humaines" link appear in the
--                                    shell navbar (see shell-layout `hasRh`).
--   RH_MANAGE_MISSION_BILLETERIE   — RH: the billeterie queue, the expense sheet and the
--                                    RH validation, plus resolving the employees' change
--                                    and cancellation requests.
--   FACT_APPROVE_MISSION_COST      — finance: the final decision on the mission cost.
--                                    FACT_-prefixed, so it lights the Finance link and
--                                    the "Missions" entry under Coûts.
--
-- NOTE (deploy): apply manually against DAF360_HR. Permissions are read into the JWT at
-- login, so a user granted one of these must LOG OUT AND BACK IN before it takes effect.

USE [DAF360_HR];
GO

DECLARE @codes TABLE (permission NVARCHAR(255));
INSERT INTO @codes VALUES
    ('RH_CREATE_MISSION'),
    ('RH_MANAGE_MISSION_BILLETERIE'),
    ('FACT_APPROVE_MISSION_COST');

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
