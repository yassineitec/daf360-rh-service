-- ============================================================
-- V76 — Registre des affectations de matériel IT (historique par employé)
--
-- Pourquoi une nouvelle table : `it_assets` est le formulaire de provisioning,
-- pas un historique. Il est rattaché à `it_provisioning` (UNE ligne par candidat,
-- créée à l'acceptation de l'offre), porte un UNIQUE (provisioning_id, asset_type_id)
-- — donc un seul PC portable par employé, à vie — et n'a AUCUNE date. Il ne peut
-- ni exprimer un remplacement, ni un retour, ni une réaffectation.
--
-- `it_asset_assignments` est le registre : une ligne = un objet détenu par un
-- employé sur une période [assigned_at, returned_at]. Plusieurs lignes du même
-- type sont attendues (PC remplacé, 2e écran, téléphone rendu puis réattribué).
--
-- ATTENTION : ddl-auto est none. ItAssetAssignment mappe ces colonnes : l'onglet
-- « Matériel IT » du profil renvoie 500 tant que ce script n'est pas appliqué.
--
--   sqlcmd -S <server> -d DAF360_HR -i V76__it_asset_assignments.sql
-- Re-jouable : création gardée, backfill gardé (anti-doublon sur la source).
-- Created: 2026-08-18
-- ============================================================

-- ── Table ────────────────────────────────────────────────────
IF OBJECT_ID('dbo.it_asset_assignments', 'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[it_asset_assignments] (
        [id]                     BIGINT IDENTITY(1,1) NOT NULL,
        [employee_profile_id]    BIGINT        NOT NULL,
        [asset_type_id]          BIGINT        NOT NULL,

        -- Identification de l'objet. serial_number est la clé métier quand elle existe.
        [serial_number]          NVARCHAR(100) NULL,
        [brand_model]            NVARCHAR(150) NULL,
        [asset_tag]              NVARCHAR(100) NULL,

        -- Période de détention. returned_at NULL = encore détenu.
        [assigned_at]            DATE          NOT NULL,
        [returned_at]            DATE          NULL,

        [condition_on_assign]    NVARCHAR(50)  NOT NULL CONSTRAINT [DF_it_asg_cond_assign] DEFAULT 'BON_ETAT',
        [condition_on_return]    NVARCHAR(50)  NULL,

        -- ASSIGNED = en cours ; les trois autres closent la ligne.
        [status]                 NVARCHAR(30)  NOT NULL CONSTRAINT [DF_it_asg_status] DEFAULT 'ASSIGNED',

        -- Provenance : d'où vient la ligne, pour ne pas confondre une saisie manuelle
        -- avec ce qui a été remis à l'embauche.
        [source]                 NVARCHAR(30)  NOT NULL CONSTRAINT [DF_it_asg_source] DEFAULT 'MANUAL',

        -- Traçabilité vers les deux systèmes existants (nullable : une affectation
        -- manuelle en cours de carrière n'a ni provisioning ni offboarding).
        [it_provisioning_id]     BIGINT        NULL,
        [offboarding_return_id]  BIGINT        NULL,

        [assigned_by]            BIGINT        NULL,
        [returned_by]            BIGINT        NULL,
        [notes]                  NVARCHAR(500) NULL,

        [created_at]             DATETIMEOFFSET(6) NOT NULL CONSTRAINT [DF_it_asg_created] DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]             DATETIMEOFFSET(6) NULL,

        CONSTRAINT [PK_it_asset_assignments] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_it_asg_profile] FOREIGN KEY ([employee_profile_id])
            REFERENCES [dbo].[employee_profiles]([id]),
        CONSTRAINT [FK_it_asg_type] FOREIGN KEY ([asset_type_id])
            REFERENCES [dbo].[it_asset_types]([id]),
        CONSTRAINT [FK_it_asg_prov] FOREIGN KEY ([it_provisioning_id])
            REFERENCES [dbo].[it_provisioning]([id]),
        CONSTRAINT [FK_it_asg_offb_return] FOREIGN KEY ([offboarding_return_id])
            REFERENCES [dbo].[offboarding_asset_returns]([id]),

        -- Mêmes valeurs que CK_it_asset_status (it_assets), pour que le libellé
        -- affiché soit le même des deux côtés. PERDU n'existe qu'au retour.
        CONSTRAINT [CK_it_asg_cond_assign] CHECK ([condition_on_assign] IN
            ('NEUF','BON_ETAT','USAGE','EN_REPARATION','DEFECTUEUX')),
        CONSTRAINT [CK_it_asg_cond_return] CHECK ([condition_on_return] IS NULL OR [condition_on_return] IN
            ('NEUF','BON_ETAT','USAGE','EN_REPARATION','DEFECTUEUX','PERDU')),
        CONSTRAINT [CK_it_asg_status] CHECK ([status] IN
            ('ASSIGNED','RETURNED','LOST','WRITTEN_OFF')),
        CONSTRAINT [CK_it_asg_source] CHECK ([source] IN
            ('ONBOARDING','MANUAL','OFFBOARDING','IMPORT')),

        -- Cohérence période / statut : ASSIGNED <=> pas de date de retour.
        CONSTRAINT [CK_it_asg_dates]  CHECK ([returned_at] IS NULL OR [returned_at] >= [assigned_at]),
        CONSTRAINT [CK_it_asg_closed] CHECK (
            ([status] = 'ASSIGNED' AND [returned_at] IS NULL)
         OR ([status] <> 'ASSIGNED' AND [returned_at] IS NOT NULL))
    );

    CREATE NONCLUSTERED INDEX [IX_it_asg_profile]
        ON [dbo].[it_asset_assignments]([employee_profile_id], [assigned_at] DESC);

    CREATE NONCLUSTERED INDEX [IX_it_asg_serial]
        ON [dbo].[it_asset_assignments]([serial_number])
        WHERE [serial_number] IS NOT NULL;

    -- Un même numéro de série ne peut pas être détenu par deux personnes en même
    -- temps. C'est ce que `it_assets` ne pouvait pas dire : rien n'y empêchait le
    -- même PC d'apparaître sur deux dossiers.
    CREATE UNIQUE NONCLUSTERED INDEX [UQ_it_asg_active_serial]
        ON [dbo].[it_asset_assignments]([serial_number])
        WHERE [serial_number] IS NOT NULL AND [returned_at] IS NULL;
END
GO

-- ============================================================
-- Backfill 1 — ce qui a été remis à l'embauche
--
-- it_assets → it_provisioning.candidate_id → employee_profiles.candidate_id.
-- Uniquement provided = 1 : une ligne à 0 est un item prévu et non remis, ce
-- n'est pas du matériel « détenu ». Date d'affectation = date d'embauche du
-- profil, à défaut la date de création du dossier de provisioning.
-- Anti-doublon : (it_provisioning_id, asset_type_id) déjà présent.
-- ============================================================
INSERT INTO [dbo].[it_asset_assignments]
    ([employee_profile_id], [asset_type_id], [serial_number], [brand_model], [asset_tag],
     [assigned_at], [condition_on_assign], [status], [source], [it_provisioning_id],
     [assigned_by], [notes], [created_at])
SELECT  p.[id],
        a.[asset_type_id],
        NULLIF(LTRIM(RTRIM(a.[serial_number])), ''),
        NULLIF(LTRIM(RTRIM(a.[brand_model])),   ''),
        NULLIF(LTRIM(RTRIM(a.[asset_tag])),     ''),
        COALESCE(p.[hire_date], CAST(prov.[created_at] AS DATE)),
        a.[status],
        'ASSIGNED',
        'ONBOARDING',
        prov.[id],
        prov.[completed_by],
        N'Repris du dossier de provisioning IT (V76)',
        SYSDATETIMEOFFSET()
FROM        [dbo].[it_assets]         a
JOIN        [dbo].[it_provisioning]   prov ON prov.[id] = a.[provisioning_id]
JOIN        [dbo].[employee_profiles] p    ON p.[candidate_id] = prov.[candidate_id]
WHERE   a.[provided] = 1
  AND   COALESCE(p.[hire_date], CAST(prov.[created_at] AS DATE)) IS NOT NULL
  AND   NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_assignments] x
                    WHERE x.[it_provisioning_id] = prov.[id]
                      AND x.[asset_type_id]      = a.[asset_type_id]);
GO

/*
 * Le backfill 1 peut buter sur UQ_it_asg_active_serial si le même numéro de série
 * a été saisi sur deux dossiers de provisioning — ce que rien n'empêchait avant.
 * L'INSERT échoue alors en entier, ce qui est voulu : la donnée doit être corrigée
 * à la main plutôt que dédoublonnée en silence. Pour lister les cas :
 *
 *   SELECT a.serial_number, COUNT(*) FROM it_assets a
 *   WHERE a.provided = 1 AND NULLIF(LTRIM(RTRIM(a.serial_number)),'') IS NOT NULL
 *   GROUP BY a.serial_number HAVING COUNT(*) > 1;
 */

-- ============================================================
-- Backfill 2 — clôture des lignes dont le matériel a déjà été rendu
--
-- offboarding_asset_returns porte la date de retour réelle et l'état constaté.
-- Rapprochement strict sur (profil, numéro de série) : un retour sans série ne
-- peut pas être rattaché à un objet précis, on laisse la ligne ouverte plutôt
-- que de clore la mauvaise.
-- ============================================================
UPDATE  asg
SET     asg.[returned_at]           = r.[actual_return_date],
        asg.[condition_on_return]   = CASE WHEN r.[condition_on_return] IN
                                            ('NEUF','BON_ETAT','USAGE','EN_REPARATION','DEFECTUEUX','PERDU')
                                           THEN r.[condition_on_return] ELSE NULL END,
        asg.[status]                = CASE WHEN r.[is_written_off] = 1 THEN 'WRITTEN_OFF' ELSE 'RETURNED' END,
        asg.[offboarding_return_id] = r.[id],
        asg.[returned_by]           = r.[confirmed_by],
        asg.[updated_at]            = SYSDATETIMEOFFSET()
FROM    [dbo].[it_asset_assignments]            asg
JOIN    [dbo].[offboarding_workflow_instances]  i ON i.[employee_profile_id] = asg.[employee_profile_id]
JOIN    [dbo].[offboarding_asset_returns]       r ON r.[workflow_instance_id] = i.[id]
                                                AND NULLIF(LTRIM(RTRIM(r.[serial_number])), '')
                                                  = asg.[serial_number]
WHERE   asg.[returned_at] IS NULL
  AND   asg.[serial_number] IS NOT NULL
  AND   r.[actual_return_date] IS NOT NULL
  AND   r.[actual_return_date] >= asg.[assigned_at];
GO

-- ============================================================
-- Permission d'écriture : RH_MANAGE_IT_ASSETS
--
-- UN seul code nouveau, pas deux. La lecture réutilise les autorisations de
-- l'onglet Contrats (HR_UPDATE_PROFILE / HR_CREATE_PROFILE / HR_ADMIN_ROLES /
-- ADMIN_ROLES) : le cookie `daf360_access` est à ~276 octets de la limite de
-- 4096 pour « Super Admin » (cf. fix_orphan_permissions.sql), donc chaque code
-- ajouté se paie sur chaque requête HTTP.
--
-- L'écriture est séparée de IT_PROVISIONING : l'IT provisionne à l'embauche,
-- mais corriger l'historique d'un employé en poste est un acte RH.
--
-- ATTENTION : le CHECK legacy CK_RolePermissions_Permission ne whitelist que
-- l'ancien jeu de codes. S'il existe encore, ce GRANT échoue — il doit être
-- supprimé (c'est déjà le cas sur les bases où les codes FACT_*/lifecycle ont
-- été accordés).
-- Les porteurs doivent SE RECONNECTER : le claim `permissions` du JWT ne se
-- rafraîchit qu'à l'ouverture de session.
-- ============================================================
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_RolePermissions_Permission')
    PRINT '[attention] CK_RolePermissions_Permission existe encore : le GRANT ci-dessous va echouer. Supprimer la contrainte d''abord.';
GO

-- Accordé aux rôles qui peuvent déjà modifier une fiche employé : ce sont eux
-- qui tiennent le registre. Tout autre rôle se règle dans /rh/admin/roles.
INSERT INTO [dbo].[RolePermissions] ([role_id], [permission])
SELECT DISTINCT rp.[role_id], N'RH_MANAGE_IT_ASSETS'
FROM   [dbo].[RolePermissions] rp
WHERE  rp.[permission] = N'HR_UPDATE_PROFILE'
  AND  NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] x
                   WHERE x.[role_id] = rp.[role_id]
                     AND x.[permission] = N'RH_MANAGE_IT_ASSETS');
GO

-- ── État final ───────────────────────────────────────────────
SELECT  [source], [status], COUNT(*) AS lignes
FROM    [dbo].[it_asset_assignments]
GROUP BY [source], [status]
ORDER BY [source], [status];
GO
