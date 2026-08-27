-- =============================================================================
-- V86__admin_sharepoint_permission.sql
-- Accorde la permission ADMIN_SHAREPOINT au role Administrateur.
--
-- Pourquoi un code dedie plutot que reutiliser HR_ADMIN_ROLES : le navigateur de
-- dossiers de l'onglet SharePoint permet d'enumerer TOUTE l'arborescence du site
-- RH (un dossier par employe, sous 01_Contracts-Employment et 03_Payroll-Admin).
-- C'est une capacite differente de "editer les roles", meme si aujourd'hui les
-- memes personnes portent les deux. Un code separe permet de la retirer sans
-- retirer l'administration des roles.
--
-- Le vocabulaire lui-meme vit dans PermissionCatalog.java (constante
-- ADMIN_SHAREPOINT, groupe "Administration") : il n'y a pas de table de reference
-- des permissions, RolePermissions porte directement le code en texte. Ce script
-- ne fait donc qu'une seule chose : l'attribution.
--
-- Les endpoints /api/hr/sharepoint/** acceptent ADMIN_SHAREPOINT *et* ADMIN_ROLES
-- / HR_ADMIN_ROLES, volontairement : appliquer ce script et deployer le code ne
-- sont pas atomiques, et l'ordre inverse ne doit pas couper l'acces a l'onglet.
--
-- Idempotente : INSERT garde par NOT EXISTS.
-- =============================================================================

USE [DAF360_HR];
GO

-- Meme forme que V54 : RolePermissions (role_id, permission), le code en clair.
-- Aucun autre role ne la recoit — la configuration des chemins se donne
-- explicitement depuis l'administration des roles, elle n'est pas ouverte par defaut.
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'ADMIN_SHAREPOINT'
FROM [dbo].[Roles] r
WHERE r.frenchName = N'Administrateur'
  AND r.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id AND rp.permission = 'ADMIN_SHAREPOINT'
  );

PRINT 'V86: Inserted ' + CAST(@@ROWCOUNT AS VARCHAR) + ' ADMIN_SHAREPOINT grant(s).';
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT r.frenchName, rp.permission
-- FROM RolePermissions rp JOIN Roles r ON r.id = rp.role_id
-- WHERE rp.permission = 'ADMIN_SHAREPOINT';
--
-- NB : une permission ajoutee n'apparait dans le JWT qu'apres re-authentification.
-- Se deconnecter/reconnecter avant de tester l'onglet.
