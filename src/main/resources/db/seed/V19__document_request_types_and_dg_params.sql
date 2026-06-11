-- V19: Seed document request types and DG parameters (applied manually 2026-06-04)
USE [DAF360_HR];
-- Request types for TN (179) and EG (53)
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=179 AND type_code='ATTESTATION_TRAVAIL')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(179,'ATTESTATION_TRAVAIL','Attestation de travail','Work Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
-- (repeat pattern for all 5 types x 2 pays — include all 10 inserts)
-- Same for ATTESTATION_SALAIRE, ATTESTATION_NON_BENEFICE_PRET, ATTESTATION_TITULARISATION, ATTESTATION_DOMICILIATION_SALAIRE
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=179 AND type_code='ATTESTATION_SALAIRE')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(179,'ATTESTATION_SALAIRE','Attestation de salaire','Salary Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=179 AND type_code='ATTESTATION_NON_BENEFICE_PRET')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(179,'ATTESTATION_NON_BENEFICE_PRET','Attestation de non-benefice de pret en cours','Non-Benefit of Loan Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=179 AND type_code='ATTESTATION_TITULARISATION')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(179,'ATTESTATION_TITULARISATION','Attestation de titularisation','Permanent Position Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=179 AND type_code='ATTESTATION_DOMICILIATION_SALAIRE')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(179,'ATTESTATION_DOMICILIATION_SALAIRE','Attestation de domiciliation de salaire','Salary Domiciliation Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=53 AND type_code='ATTESTATION_TRAVAIL')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(53,'ATTESTATION_TRAVAIL','Attestation de travail','Work Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=53 AND type_code='ATTESTATION_SALAIRE')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(53,'ATTESTATION_SALAIRE','Attestation de salaire','Salary Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=53 AND type_code='ATTESTATION_NON_BENEFICE_PRET')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(53,'ATTESTATION_NON_BENEFICE_PRET','Attestation de non-benefice de pret en cours','Non-Benefit of Loan Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=53 AND type_code='ATTESTATION_TITULARISATION')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(53,'ATTESTATION_TITULARISATION','Attestation de titularisation','Permanent Position Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[request_type_catalog] WHERE pays_id=53 AND type_code='ATTESTATION_DOMICILIATION_SALAIRE')
    INSERT INTO [dbo].[request_type_catalog](pays_id,type_code,display_name_fr,display_name_en,category,approval_level,default_sla_days,is_active,created_at)
    VALUES(53,'ATTESTATION_DOMICILIATION_SALAIRE','Attestation de domiciliation de salaire','Salary Domiciliation Certificate','DOCUMENT','L1',2,1,SYSDATETIMEOFFSET());

-- DG parameters
DECLARE @tnId BIGINT = (SELECT id FROM [dbo].[pays] WHERE iso_code='TN');
IF @tnId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id=@tnId AND cle='DG_NAME')
    INSERT INTO [dbo].[parameter_sets](pays_id,cle,valeur,description) VALUES(@tnId,'DG_NAME','Fahed CHEBBI','Nom du Directeur General');
IF @tnId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id=@tnId AND cle='DG_CIN')
    INSERT INTO [dbo].[parameter_sets](pays_id,cle,valeur,description) VALUES(@tnId,'DG_CIN','00365675','CIN du Directeur General');
IF @tnId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id=@tnId AND cle='DG_CIN_DATE')
    INSERT INTO [dbo].[parameter_sets](pays_id,cle,valeur,description) VALUES(@tnId,'DG_CIN_DATE','06 Decembre 2016','Date de delivrance CIN du DG');
IF @tnId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id=@tnId AND cle='DG_CIN_CITY')
    INSERT INTO [dbo].[parameter_sets](pays_id,cle,valeur,description) VALUES(@tnId,'DG_CIN_CITY','Tunis','Ville de delivrance CIN du DG');
IF @tnId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id=@tnId AND cle='DG_TITLE')
    INSERT INTO [dbo].[parameter_sets](pays_id,cle,valeur,description) VALUES(@tnId,'DG_TITLE','Gerant','Titre du Directeur General');
