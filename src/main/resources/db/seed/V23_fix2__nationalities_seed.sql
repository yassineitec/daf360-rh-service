-- =============================================================================
-- V23_FIX2: Populate nationalities table with complete list
-- Run in SSMS — idempotent (NOT EXISTS guard per row)
-- Labels in French, English labels and ISO 3166-1 alpha-2 codes included
-- =============================================================================
USE [DAF360_HR];
GO

-- Helper: insert only if the nationality doesn't already exist
-- Maghreb & MENA (priority for ITEC Groupe context)
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Tunisienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Tunisienne', N'Tunisian', 'TN', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Algérienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Algérienne', N'Algerian', 'DZ', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Marocaine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Marocaine', N'Moroccan', 'MA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Libyenne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Libyenne', N'Libyan', 'LY', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Égyptienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Égyptienne', N'Egyptian', 'EG', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Mauritanienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Mauritanienne', N'Mauritanian', 'MR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Française')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Française', N'French', 'FR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Belge')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Belge', N'Belgian', 'BE', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Suisse')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Suisse', N'Swiss', 'CH', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Canadienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Canadienne', N'Canadian', 'CA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Saoudienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Saoudienne', N'Saudi Arabian', 'SA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Émiratie')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Émiratie', N'Emirati', 'AE', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Qatarienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Qatarienne', N'Qatari', 'QA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Koweïtienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Koweïtienne', N'Kuwaiti', 'KW', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Jordanienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Jordanienne', N'Jordanian', 'JO', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Libanaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Libanaise', N'Lebanese', 'LB', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Syrienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Syrienne', N'Syrian', 'SY', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Irakienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Irakienne', N'Iraqi', 'IQ', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Yéménite')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Yéménite', N'Yemeni', 'YE', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Bahreïnienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Bahreïnienne', N'Bahraini', 'BH', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Omanaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Omanaise', N'Omani', 'OM', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Palestinienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Palestinienne', N'Palestinian', 'PS', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Somalienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Somalienne', N'Somali', 'SO', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Soudanaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Soudanaise', N'Sudanese', 'SD', 1);
GO

-- Europe
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Allemande')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Allemande', N'German', 'DE', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Espagnole')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Espagnole', N'Spanish', 'ES', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Italienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Italienne', N'Italian', 'IT', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Portugaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Portugaise', N'Portuguese', 'PT', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Britannique')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Britannique', N'British', 'GB', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Néerlandaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Néerlandaise', N'Dutch', 'NL', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Polonaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Polonaise', N'Polish', 'PL', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Roumaine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Roumaine', N'Romanian', 'RO', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Grecque')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Grecque', N'Greek', 'GR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Turque')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Turque', N'Turkish', 'TR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Russe')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Russe', N'Russian', 'RU', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Ukrainienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Ukrainienne', N'Ukrainian', 'UA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Suédoise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Suédoise', N'Swedish', 'SE', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Norvégienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Norvégienne', N'Norwegian', 'NO', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Danoise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Danoise', N'Danish', 'DK', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Finlandaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Finlandaise', N'Finnish', 'FI', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Autrichienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Autrichienne', N'Austrian', 'AT', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Hongroise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Hongroise', N'Hungarian', 'HU', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Tchèque')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Tchèque', N'Czech', 'CZ', 1);
GO

-- Afrique subsaharienne
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Sénégalaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Sénégalaise', N'Senegalese', 'SN', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Ivoirienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Ivoirienne', N'Ivorian', 'CI', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Camerounaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Camerounaise', N'Cameroonian', 'CM', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Nigériane')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Nigériane', N'Nigerian', 'NG', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Ghanéenne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Ghanéenne', N'Ghanaian', 'GH', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Sud-Africaine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Sud-Africaine', N'South African', 'ZA', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Éthiopienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Éthiopienne', N'Ethiopian', 'ET', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Kényane')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Kényane', N'Kenyan', 'KE', 1);
GO

-- Asie
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Indienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Indienne', N'Indian', 'IN', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Pakistanaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Pakistanaise', N'Pakistani', 'PK', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Bangladaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Bangladaise', N'Bangladeshi', 'BD', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Chinoise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Chinoise', N'Chinese', 'CN', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Japonaise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Japonaise', N'Japanese', 'JP', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Coréenne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Coréenne', N'Korean', 'KR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Vietnamienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Vietnamienne', N'Vietnamese', 'VN', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Philippinoise')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Philippinoise', N'Filipino', 'PH', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Indonésienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Indonésienne', N'Indonesian', 'ID', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Malaisienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Malaisienne', N'Malaysian', 'MY', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Iranienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Iranienne', N'Iranian', 'IR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Afghane')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Afghane', N'Afghan', 'AF', 1);
GO

-- Amériques
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Américaine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Américaine', N'American', 'US', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Brésilienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Brésilienne', N'Brazilian', 'BR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Mexicaine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Mexicaine', N'Mexican', 'MX', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Argentine')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Argentine', N'Argentine', 'AR', 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] WHERE label_fr = N'Colombienne')
    INSERT INTO [dbo].[nationalities] (label_fr, label_en, iso_code, is_active) VALUES (N'Colombienne', N'Colombian', 'CO', 1);
GO

-- Vérification finale
SELECT COUNT(*) AS total_nationalities FROM [dbo].[nationalities];
GO
