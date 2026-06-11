-- V9__configurable_lists_seed.sql (fixed)
-- Supplies created_at explicitly so DEFAULT constraints are not needed.

USE [DAF360_HR];

-- ============================================================================
-- 1. LIST TYPES  (include created_at = GETDATE() explicitly)
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='GENDER')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('GENDER','Genre','Gender',0,1,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='MARITAL_STATUS')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('MARITAL_STATUS','Situation familiale','Marital Status',0,1,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='AD_PROFILE_TYPE')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,description,created_at)
    VALUES ('AD_PROFILE_TYPE','Type de profil AD','AD Profile Type',1,0,'Profils AD assignes lors du provisioning IT',GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='GRADE')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('GRADE','Grade','Grade',1,0,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='DISCIPLINE')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('DISCIPLINE','Discipline','Discipline',1,0,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='NOG_LEVEL')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('NOG_LEVEL','Niveau NOG','NOG Level',0,0,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='BANK_NAME')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('BANK_NAME','Banque','Bank',1,0,GETDATE());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='DOCUMENT_SLOT')
    INSERT INTO [dbo].[configurable_list_types] (code,label_fr,label_en,is_per_pays,is_system,created_at)
    VALUES ('DOCUMENT_SLOT','Document requis onboarding','Required Document',0,1,GETDATE());

-- ============================================================================
-- 2. GENDER values  (include created_at = SYSDATETIMEOFFSET() explicitly)
-- ============================================================================

DECLARE @gId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='GENDER');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@gId AND value_code='MALE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@gId,'MALE','Homme','Male',1,NULL,1,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@gId AND value_code='FEMALE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@gId,'FEMALE','Femme','Female',1,NULL,2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@gId AND value_code='OTHER')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@gId,'OTHER','Autre','Other',1,NULL,3,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@gId AND value_code='UNSPECIFIED')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@gId,'UNSPECIFIED','Non specifie','Unspecified',1,NULL,4,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 3. MARITAL_STATUS values
-- ============================================================================

DECLARE @mId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='MARITAL_STATUS');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@mId AND value_code='SINGLE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@mId,'SINGLE','Celibataire','Single',1,NULL,1,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@mId AND value_code='MARRIED')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@mId,'MARRIED','Marie(e)','Married',1,NULL,2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@mId AND value_code='DIVORCED')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@mId,'DIVORCED','Divorce(e)','Divorced',1,NULL,3,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@mId AND value_code='WIDOWED')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@mId,'WIDOWED','Veuf-Veuve','Widowed',1,NULL,4,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 4. AD_PROFILE_TYPE values
-- ============================================================================

DECLARE @adId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='AD_PROFILE_TYPE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='INGENIEUR_VRD')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'INGENIEUR_VRD','Ingenieur VRD','VRD Engineer',0,NULL,1,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='DESSINATEUR')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'DESSINATEUR','Dessinateur','Drafter',0,NULL,2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='CHEF_PROJET')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'CHEF_PROJET','Chef de projet','Project Manager',0,NULL,3,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='RESPONSABLE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'RESPONSABLE','Responsable','Manager',0,NULL,4,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='ADMINISTRATIF')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'ADMINISTRATIF','Administratif','Administrative',0,NULL,5,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@adId AND value_code='AUTRE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@adId,'AUTRE','Autre','Other',0,NULL,6,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 5. GRADE values
-- ============================================================================

DECLARE @grId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='GRADE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@grId AND value_code='JUNIOR')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@grId,'JUNIOR','Junior','Junior',0,NULL,1,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@grId AND value_code='CONFIRME')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@grId,'CONFIRME','Confirme','Confirmed',0,NULL,2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@grId AND value_code='SENIOR')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@grId,'SENIOR','Senior','Senior',0,NULL,3,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@grId AND value_code='LEAD')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@grId,'LEAD','Lead','Lead',0,NULL,4,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@grId AND value_code='MANAGER')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@grId,'MANAGER','Manager','Manager',0,NULL,5,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 6. DISCIPLINE values
-- ============================================================================

DECLARE @dId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='DISCIPLINE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='VRD')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'VRD','Voirie Reseaux Divers','Road Infrastructure',0,NULL,1,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='STRUCTURE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'STRUCTURE','Structure','Structural Engineering',0,NULL,2,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='ARCHITECTURE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'ARCHITECTURE','Architecture','Architecture',0,NULL,3,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='HYDRAULIQUE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'HYDRAULIQUE','Hydraulique','Hydraulics',0,NULL,4,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='ELECTRICITE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'ELECTRICITE','Electricite','Electrical',0,NULL,5,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='INFORMATIQUE')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'INFORMATIQUE','Informatique','IT',0,NULL,6,1,SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dId AND value_code='AUTRE_DISC')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at)
    VALUES (@dId,'AUTRE_DISC','Autre','Other',0,NULL,7,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 7. NOG_LEVEL values
-- ============================================================================

DECLARE @nId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='NOG_LEVEL');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='P1')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'P1','P1','P1',0,NULL,1,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='P2')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'P2','P2','P2',0,NULL,2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='P3')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'P3','P3','P3',0,NULL,3,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='P4')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'P4','P4','P4',0,NULL,4,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='M1')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'M1','M1','M1',0,NULL,5,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='M2')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'M2','M2','M2',0,NULL,6,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='D1')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'D1','D1','D1',0,NULL,7,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@nId AND value_code='D2')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@nId,'D2','D2','D2',0,NULL,8,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 8. BANK_NAME values
-- ============================================================================

DECLARE @bId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='BANK_NAME');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='BNA')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'BNA','Banque Nationale Agricole','BNA',0,NULL,1,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='STB')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'STB','Societe Tunisienne de Banque','STB',0,NULL,2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='ATTIJARI')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'ATTIJARI','Attijari Wafabank','Attijari Wafabank',0,NULL,3,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='BIAT')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'BIAT','BIAT','BIAT',0,NULL,4,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='BH_BANK')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'BH_BANK','BH Bank','BH Bank',0,NULL,5,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@bId AND value_code='AUTRE_BANK')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@bId,'AUTRE_BANK','Autre banque','Other',0,NULL,6,1,SYSDATETIMEOFFSET());

-- ============================================================================
-- 9. DOCUMENT_SLOT values
-- ============================================================================

DECLARE @dsId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='DOCUMENT_SLOT');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='CIN_PASSEPORT')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'CIN_PASSEPORT','CIN-Passeport','ID Card-Passport',1,NULL,1,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='CONTRAT_TRAVAIL')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'CONTRAT_TRAVAIL','Contrat de travail','Employment Contract',1,NULL,2,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='PHOTO_ID')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'PHOTO_ID','Photo identite','ID Photo',1,NULL,3,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='RIB')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'RIB','RIB bancaire','Bank RIB',1,NULL,4,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='CNSS')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'CNSS','CNSS','CNSS',1,NULL,5,1,SYSDATETIMEOFFSET());
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@dsId AND value_code='CERT_SCOL')
    INSERT INTO [dbo].[configurable_list_values] (list_type_id,value_code,label_fr,label_en,is_system,pays_id,sort_order,is_active,created_at) VALUES (@dsId,'CERT_SCOL','Certificat de scolarite','School Certificate',0,NULL,6,1,SYSDATETIMEOFFSET());

PRINT 'V9 seed complete.';