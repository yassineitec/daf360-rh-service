-- =============================================================================
-- V27: Overtime configuration per country (Heures Supplémentaires)
--      TypeCalculHS: WEEKEND_ONLY / AFTER_WORK_HOURS / MIXTE
--      WEEKEND_ONLY: HS if workDate is in pays_weekends
--      AFTER_WORK_HOURS: HS if workEnd > heureFinTravail or workStart < heureDebutTravail
--      MIXTE: union of both — weekend hours + off-schedule hours on weekdays
-- =============================================================================
USE [DAF360_HR];
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_NAME='parametrage_heures_supp_pays' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[parametrage_heures_supp_pays] (
        [id_parametrage]       BIGINT IDENTITY(1,1)  NOT NULL,
        [pays_id]              BIGINT                NOT NULL,
        [type_calcul_hs]       VARCHAR(20)           NOT NULL,
        [heure_debut_travail]  TIME(0)               NULL,
        [heure_fin_travail]    TIME(0)               NULL,
        [jour_debut_semaine]   VARCHAR(10)           NULL,  -- ex: MONDAY
        [jour_fin_semaine]     VARCHAR(10)           NULL,  -- ex: FRIDAY
        [actif]                BIT                   NOT NULL DEFAULT 1,
        [date_creation]        DATETIMEOFFSET        NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [date_modification]    DATETIMEOFFSET        NULL,
        [created_by]           BIGINT                NULL,
        CONSTRAINT [PK_hs_parametrage]      PRIMARY KEY CLUSTERED ([id_parametrage] ASC),
        CONSTRAINT [FK_hs_pays]             FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_hs_created_by]       FOREIGN KEY ([created_by]) REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_hs_type_calcul]      CHECK ([type_calcul_hs] IN ('WEEKEND_ONLY','AFTER_WORK_HOURS','MIXTE'))
    );
    CREATE NONCLUSTERED INDEX [IX_hs_pays] ON [dbo].[parametrage_heures_supp_pays]([pays_id]);
END
GO

-- Seed: Tunisia → WEEKEND_ONLY
IF NOT EXISTS (SELECT 1 FROM [dbo].[parametrage_heures_supp_pays] ph
               JOIN [dbo].[pays] p ON p.id = ph.pays_id WHERE p.iso_code='TN')
    INSERT INTO [dbo].[parametrage_heures_supp_pays]
        (pays_id, type_calcul_hs, jour_debut_semaine, jour_fin_semaine, actif)
    SELECT p.id, 'WEEKEND_ONLY', 'MONDAY', 'THURSDAY', 1
    FROM [dbo].[pays] p WHERE p.iso_code = 'TN' AND (p.deleted=0 OR p.deleted IS NULL);
GO

-- Seed: Egypt → AFTER_WORK_HOURS (normal end = 17:30)
IF NOT EXISTS (SELECT 1 FROM [dbo].[parametrage_heures_supp_pays] ph
               JOIN [dbo].[pays] p ON p.id = ph.pays_id WHERE p.iso_code='EG')
    INSERT INTO [dbo].[parametrage_heures_supp_pays]
        (pays_id, type_calcul_hs, heure_debut_travail, heure_fin_travail, jour_debut_semaine, jour_fin_semaine, actif)
    SELECT p.id, 'AFTER_WORK_HOURS', '08:30', '17:30', 'MONDAY', 'THURSDAY', 1
    FROM [dbo].[pays] p WHERE p.iso_code = 'EG' AND (p.deleted=0 OR p.deleted IS NULL);
GO
