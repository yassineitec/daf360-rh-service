-- =============================================================================
-- V25: Contract history module
--      type_contrat      : reference table (global, no pays_id)
--      historique_contrat_collaborateur : one row per contract/amendment
-- =============================================================================
USE [DAF360_HR];
GO

-- 1. Create type_contrat reference table
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_NAME='type_contrat' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[type_contrat] (
        [id]         BIGINT IDENTITY(1,1) NOT NULL,
        [code]       VARCHAR(50)   NOT NULL,
        [label_fr]   NVARCHAR(100) NOT NULL,
        [label_en]   NVARCHAR(100) NOT NULL,
        [is_active]  BIT           NOT NULL DEFAULT 1,
        [created_at] DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_type_contrat] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [UQ_type_contrat_code] UNIQUE ([code])
    );

    INSERT INTO [dbo].[type_contrat] (code, label_fr, label_en, is_active) VALUES
        ('CDI',             N'Contrat à Durée Indéterminée',       N'Permanent Contract',         1),
        ('CDD',             N'Contrat à Durée Déterminée',          N'Fixed-Term Contract',        1),
        ('INTERN',          N'Convention de stage',                  N'Internship Agreement',       1),
        ('CONSULTANT',      N'Contrat consultant',                   N'Consulting Contract',        1),
        ('FREELANCE',       N'Contrat freelance',                    N'Freelance Contract',         1),
        ('AVENANT_SALAIRE', N'Avenant — Revalorisation salariale',  N'Amendment — Salary Increase',1),
        ('AVENANT_POSTE',   N'Avenant — Changement de poste',       N'Amendment — Position Change',1),
        ('AVENANT_DUREE',   N'Avenant — Prolongation',              N'Amendment — Extension',      1);
END
GO

-- 2. Create historique_contrat_collaborateur
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_NAME='historique_contrat_collaborateur' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[historique_contrat_collaborateur] (
        [id_historique_contrat] BIGINT IDENTITY(1,1) NOT NULL,
        [id_collaborateur]      BIGINT        NOT NULL,
        [id_type_contrat]       BIGINT        NOT NULL,
        [type_document]         VARCHAR(20)   NOT NULL,
        [date_effet]            DATE          NOT NULL,
        [date_fin]              DATE          NULL,
        [salaire_net]           DECIMAL(10,3) NULL,
        [motif]                 NVARCHAR(255) NULL,
        [commentaire]           NVARCHAR(1000) NULL,
        [created_by]            BIGINT        NULL,
        [date_creation]         DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT [PK_historique_contrat] PRIMARY KEY CLUSTERED ([id_historique_contrat] ASC),
        CONSTRAINT [FK_HCC_Collaborateur] FOREIGN KEY ([id_collaborateur])
            REFERENCES [dbo].[employee_profiles]([id]),
        CONSTRAINT [FK_HCC_TypeContrat] FOREIGN KEY ([id_type_contrat])
            REFERENCES [dbo].[type_contrat]([id]),
        CONSTRAINT [FK_HCC_CreatedBy] FOREIGN KEY ([created_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_HCC_TypeDocument] CHECK ([type_document] IN ('CONTRAT_INITIAL','AVENANT'))
    );

    CREATE NONCLUSTERED INDEX [IX_HCC_Collaborateur]
        ON [dbo].[historique_contrat_collaborateur]([id_collaborateur]);
    CREATE NONCLUSTERED INDEX [IX_HCC_DateEffet]
        ON [dbo].[historique_contrat_collaborateur]([id_collaborateur],[date_effet]);
END
GO
