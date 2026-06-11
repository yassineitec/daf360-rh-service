-- =============================================================================
-- V23_FIX3: Seed all dimension tables (grades, disciplines, nog_levels,
--            departments, banks) for all active pays
-- Idempotent — NOT EXISTS guard per entry
-- Adapted for ITEC Groupe context (ingénierie / conseil / BTP)
-- =============================================================================
USE [DAF360_HR];
GO

-- =============================================================================
-- Helper: get pays IDs
-- =============================================================================
-- We seed per pays. For pays-agnostic data we loop all active pays.
-- Run AFTER V23_fix (it_asset_types) and V23_fix2 (nationalities).

-- =============================================================================
-- SECTION 1: GRADES (par pays)
-- Common grades in engineering consulting firms
-- =============================================================================

INSERT INTO [dbo].[grades] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT p.id, g.code, g.label_fr, g.label_en, g.sort_order, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('STAGIAIRE',   N'Stagiaire',              N'Intern',               1),
    ('JUNIOR',      N'Junior',                 N'Junior',               2),
    ('CONFIRME',    N'Confirmé',               N'Confirmed',            3),
    ('SENIOR',      N'Senior',                 N'Senior',               4),
    ('LEAD',        N'Lead / Référent',        N'Lead / Reference',     5),
    ('EXPERT',      N'Expert',                 N'Expert',               6),
    ('MANAGER',     N'Manager',                N'Manager',              7),
    ('DIRECTEUR',   N'Directeur',              N'Director',             8)
) AS g(code, label_fr, label_en, sort_order)
WHERE p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[grades] gr
      WHERE gr.pays_id = p.id AND gr.code = g.code
  );
GO

-- =============================================================================
-- SECTION 2: DISCIPLINES (par pays)
-- Engineering + support disciplines
-- =============================================================================

INSERT INTO [dbo].[disciplines] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT p.id, d.code, d.label_fr, d.label_en, d.sort_order, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('GENIE_CIVIL',      N'Génie Civil',               N'Civil Engineering',        1),
    ('STRUCTURE',        N'Structure / Calcul',         N'Structural Engineering',   2),
    ('HYDRAULIQUE',      N'Hydraulique / VRD',          N'Hydraulics / Utilities',   3),
    ('GEOTECHNIQUE',     N'Géotechnique',               N'Geotechnics',              4),
    ('GENIE_ELEC',       N'Génie Électrique',           N'Electrical Engineering',   5),
    ('GENIE_MECA',       N'Génie Mécanique',            N'Mechanical Engineering',   6),
    ('ARCHITECTURE',     N'Architecture',               N'Architecture',             7),
    ('TOPOGRAPHIE',      N'Topographie / Géomètre',     N'Surveying',                8),
    ('ENVIRONNEMENT',    N'Environnement / HSE',        N'Environment / HSE',        9),
    ('INFORMATIQUE',     N'Informatique / SI',          N'IT / Information Systems', 10),
    ('FINANCE',          N'Finance & Comptabilité',     N'Finance & Accounting',     11),
    ('RH',               N'Ressources Humaines',        N'Human Resources',          12),
    ('ADMINISTRATION',   N'Administration',             N'Administration',           13),
    ('COMMERCIAL',       N'Commercial & Marketing',     N'Sales & Marketing',        14),
    ('JURIDIQUE',        N'Juridique',                  N'Legal',                    15),
    ('QUALITE',          N'Qualité / Contrôle',         N'Quality / Control',        16),
    ('LOGISTIQUE',       N'Logistique',                 N'Logistics',                17),
    ('PLANIFICATION',    N'Planification / Ordonnancement', N'Planning / Scheduling', 18)
) AS d(code, label_fr, label_en, sort_order)
WHERE p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[disciplines] di
      WHERE di.pays_id = p.id AND di.code = d.code
  );
GO

-- =============================================================================
-- SECTION 3: NOG LEVELS (par pays)
-- Niveau Opérationnel Général — classification hiérarchique en ingénierie
-- =============================================================================

INSERT INTO [dbo].[nog_levels] (pays_id, code, label_fr, label_en, level_order, is_active)
SELECT p.id, n.code, n.label_fr, n.label_en, n.level_order, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('NOG1', N'NOG 1 — Agent d''exécution',       N'NOG 1 — Execution Agent',        1),
    ('NOG2', N'NOG 2 — Technicien',                N'NOG 2 — Technician',             2),
    ('NOG3', N'NOG 3 — Technicien Supérieur',      N'NOG 3 — Senior Technician',      3),
    ('NOG4', N'NOG 4 — Ingénieur Junior',          N'NOG 4 — Junior Engineer',        4),
    ('NOG5', N'NOG 5 — Ingénieur',                 N'NOG 5 — Engineer',               5),
    ('NOG6', N'NOG 6 — Ingénieur Confirmé',        N'NOG 6 — Confirmed Engineer',     6),
    ('NOG7', N'NOG 7 — Ingénieur Senior / Expert', N'NOG 7 — Senior Engineer / Expert', 7),
    ('NOG8', N'NOG 8 — Chef de Projet / Manager',  N'NOG 8 — Project Manager',        8),
    ('NOG9', N'NOG 9 — Directeur de Projet',       N'NOG 9 — Project Director',       9),
    ('NOG10',N'NOG 10 — Directeur',                N'NOG 10 — Director',              10)
) AS n(code, label_fr, label_en, level_order)
WHERE p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[nog_levels] nl
      WHERE nl.pays_id = p.id AND nl.code = n.code
  );
GO

-- =============================================================================
-- SECTION 4: DEPARTMENTS (par pays)
-- Structure organisationnelle typique ITEC Groupe
-- =============================================================================

INSERT INTO [dbo].[departments] (pays_id, code, label_fr, label_en, is_active)
SELECT p.id, d.code, d.label_fr, d.label_en, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('DG',          N'Direction Générale',                     N'General Management'),
    ('RH',          N'Ressources Humaines',                    N'Human Resources'),
    ('FIN',         N'Finance & Comptabilité',                 N'Finance & Accounting'),
    ('IT',          N'Informatique & Systèmes d''Information', N'IT & Information Systems'),
    ('GC',          N'Génie Civil & Structure',                N'Civil & Structural Engineering'),
    ('GE',          N'Génie Électrique',                       N'Electrical Engineering'),
    ('GM',          N'Génie Mécanique',                        N'Mechanical Engineering'),
    ('HYD',         N'Hydraulique & Environnement',            N'Hydraulics & Environment'),
    ('ARCHI',       N'Architecture & Urbanisme',               N'Architecture & Urban Planning'),
    ('TOPO',        N'Topographie & Géodésie',                 N'Surveying & Geodesy'),
    ('ETUDES',      N'Bureau d''Études & R&D',                 N'Engineering & R&D'),
    ('PROJET',      N'Gestion de Projets',                     N'Project Management'),
    ('COM',         N'Commercial & Développement',             N'Sales & Business Development'),
    ('QUALITE',     N'Qualité, Sécurité & Environnement',      N'Quality, Safety & Environment'),
    ('ADMIN',       N'Administration & Logistique',            N'Administration & Logistics'),
    ('JURIDIQUE',   N'Juridique & Contrats',                   N'Legal & Contracts')
) AS d(code, label_fr, label_en)
WHERE p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[departments] dept
      WHERE dept.pays_id = p.id AND dept.code = d.code
  );
GO

-- =============================================================================
-- SECTION 5: BANKS — Tunisie (iso_code = 'TN')
-- =============================================================================

INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, swift_code, is_active)
SELECT p.id, b.code, b.label_fr, b.label_en, b.swift_code, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('STB',     N'Société Tunisienne de Banque',           N'STB Bank',                   'STBKTNTT'),
    ('BNA',     N'Banque Nationale Agricole',              N'National Agricultural Bank',  'BNATTNTT'),
    ('BH',      N'BH Bank',                                N'BH Bank',                    'BHBKTNTT'),
    ('BIAT',    N'Banque Internationale Arabe de Tunisie', N'BIAT',                        'BIATTNTT'),
    ('ATTIJARI',N'Attijari Bank Tunisie',                  N'Attijari Bank Tunisia',       'BSTUTNTT'),
    ('UIB',     N'Union Internationale de Banques',        N'UIB',                         'UIBITNTT'),
    ('AMEN',    N'Amen Bank',                              N'Amen Bank',                   'CFCTTNTT'),
    ('ATB',     N'Arab Tunisian Bank',                     N'Arab Tunisian Bank',          'ATBKTNTT'),
    ('ZITOUNA', N'Banque Zitouna',                         N'Zitouna Bank',                'ZITOTNTT'),
    ('ALBARAKA',N'Al Baraka Bank Tunisie',                 N'Al Baraka Bank Tunisia',      'BARKTNTT'),
    ('WIFACK',  N'Wifack International Bank',              N'Wifack Bank',                 'WIFKTNTT'),
    ('BT',      N'Banque de Tunisie',                      N'Banque de Tunisie',           'BTTNTNTT'),
    ('STUSID',  N'Stusid Bank',                            N'Stusid Bank',                 'STUSBEBB'),
    ('QNB',     N'Qatar National Bank Tunisie',            N'QNB Tunisia',                 'QNBATNTX'),
    ('POSTE',   N'La Poste Tunisienne (CCP)',              N'Tunisian Post (CCP)',          NULL)
) AS b(code, label_fr, label_en, swift_code)
WHERE p.iso_code = 'TN' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[banks] bk
      WHERE bk.pays_id = p.id AND bk.code = b.code
  );
GO

-- =============================================================================
-- SECTION 6: BANKS — Algérie (iso_code = 'DZ')
-- =============================================================================

INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, swift_code, is_active)
SELECT p.id, b.code, b.label_fr, b.label_en, b.swift_code, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('BNA_DZ',   N'Banque Nationale d''Algérie',            N'National Bank of Algeria',    'BNALDZDZ'),
    ('BEA',      N'Banque Extérieure d''Algérie',           N'Bank of Algeria External',    'BEAADZAL'),
    ('CPA',      N'Crédit Populaire d''Algérie',            N'Popular Credit of Algeria',   'CPAALDZZ'),
    ('BDL',      N'Banque de Développement Local',          N'Local Development Bank',      'BDLADZAL'),
    ('CNEP',     N'CNEP-Banque',                            N'CNEP Bank',                   'CNEPDZAL'),
    ('ABC_DZ',   N'Arab Banking Corporation Algérie',       N'ABC Algeria',                 'ABCODZA1'),
    ('GULF_DZ',  N'Gulf Bank Algeria',                      N'Gulf Bank Algeria',           NULL),
    ('AGB',      N'Algerian Gulf Bank',                     N'Algerian Gulf Bank',          'AGBAALGE'),
    ('NATIXIS_DZ',N'Natixis Algérie',                       N'Natixis Algeria',             NULL),
    ('SOCIETE_DZ',N'Société Générale Algérie',              N'Société Générale Algeria',    NULL),
    ('BNP_DZ',   N'BNP Paribas El Djazaïr',                N'BNP Paribas Algeria',         NULL),
    ('BARAKA_DZ',N'Al Baraka Bank Algérie',                 N'Al Baraka Bank Algeria',      'BARKDZAL'),
    ('AXA_DZ',   N'Fransabank El Djazaïr',                  N'Fransabank Algeria',          NULL),
    ('TRUST',    N'Trust Bank Algeria',                     N'Trust Bank Algeria',          NULL),
    ('POSTE_DZ', N'Algérie Poste (CCP)',                    N'Algeria Post (CCP)',           NULL)
) AS b(code, label_fr, label_en, swift_code)
WHERE p.iso_code = 'DZ' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[banks] bk
      WHERE bk.pays_id = p.id AND bk.code = b.code
  );
GO

-- =============================================================================
-- SECTION 7: BANKS — Égypte (iso_code = 'EG')
-- =============================================================================

INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, swift_code, is_active)
SELECT p.id, b.code, b.label_fr, b.label_en, b.swift_code, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('NBE',      N'National Bank of Egypt',                N'National Bank of Egypt',      'NBEGEGCX'),
    ('BANQUE_MSR',N'Banque Misr',                          N'Banque Misr',                 'BMISEGCX'),
    ('QNB_EG',   N'Qatar National Bank Al Ahli',           N'QNB Al Ahli',                 'QNBAEGCX'),
    ('CIB',      N'Commercial International Bank',         N'CIB Egypt',                   'CIBEEGCX'),
    ('HSBC_EG',  N'HSBC Égypte',                          N'HSBC Egypt',                  'BBMEEGCX'),
    ('SCB_EG',   N'Standard Chartered Bank Egypt',         N'SCB Egypt',                   'SCBLEGD1'),
    ('ABE',      N'Arab Bank Egypt',                       N'Arab Bank Egypt',             'ARABEGCX'),
    ('AHLI_EG',  N'Ahli United Bank',                      N'Ahli United Bank Egypt',      'AHLIEGCX'),
    ('FAISAL',   N'Faisal Islamic Bank of Egypt',          N'Faisal Islamic Bank',         'FIBKEGCX'),
    ('ABB',      N'Al Baraka Bank Egypt',                  N'Al Baraka Bank Egypt',        'BARKEGCX'),
    ('POSTALE_EG',N'Banque Postale d''Égypte',             N'Egypt Post Bank',             NULL)
) AS b(code, label_fr, label_en, swift_code)
WHERE p.iso_code = 'EG' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[banks] bk
      WHERE bk.pays_id = p.id AND bk.code = b.code
  );
GO

-- =============================================================================
-- SECTION 8: BANKS — France (iso_code = 'FR') — si pays FR existe
-- =============================================================================

INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, swift_code, is_active)
SELECT p.id, b.code, b.label_fr, b.label_en, b.swift_code, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('BNP_FR',    N'BNP Paribas',                N'BNP Paribas',             'BNPAFRPP'),
    ('SG_FR',     N'Société Générale',            N'Société Générale',        'SOGEFRPP'),
    ('CA_FR',     N'Crédit Agricole',             N'Crédit Agricole',         'AGRIFRPP'),
    ('LCL',       N'LCL (Crédit Lyonnais)',       N'LCL',                    'CRLYFRPP'),
    ('BRED',      N'BRED Banque Populaire',       N'BRED',                   'BREDFRPP'),
    ('CIC',       N'CIC',                         N'CIC',                    'CMCIFRPP'),
    ('CE_FR',     N'Caisse d''Épargne',           N'Caisse d''Épargne',      'CEPAFRPP'),
    ('HSBC_FR',   N'HSBC France',                 N'HSBC France',            'CCFRFRPP'),
    ('LA_POSTE',  N'La Banque Postale',           N'La Banque Postale',      'PSSTFRPP')
) AS b(code, label_fr, label_en, swift_code)
WHERE p.iso_code = 'FR' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[banks] bk
      WHERE bk.pays_id = p.id AND bk.code = b.code
  );
GO

-- =============================================================================
-- SECTION 9: BANKS — Maroc (iso_code = 'MA') — si pays MA existe
-- =============================================================================

INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, swift_code, is_active)
SELECT p.id, b.code, b.label_fr, b.label_en, b.swift_code, 1
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('ATT_MA',  N'Attijariwafa Bank',              N'Attijariwafa Bank',      'BCDMMAMC'),
    ('BCP',     N'Banque Centrale Populaire',      N'Banque Centrale Populaire', 'BCPOMAMC'),
    ('BMCE',    N'BMCE Bank of Africa',            N'BMCE Bank',              'BMCEMAMC'),
    ('SGMA',    N'Société Générale Maroc',         N'Société Générale Maroc', 'SGMBMAMC'),
    ('BMCI',    N'BMCI (BNP Paribas Maroc)',       N'BMCI',                   'BMCIMAMC'),
    ('CDM',     N'Crédit du Maroc',                N'Crédit du Maroc',        'CDMAMAMC'),
    ('CFG',     N'CFG Bank',                       N'CFG Bank',               NULL),
    ('CAM',     N'Crédit Agricole du Maroc',       N'Crédit Agricole Maroc',  'CAMAMAMC'),
    ('CIH',     N'CIH Bank',                       N'CIH Bank',               'NIHBMAMC'),
    ('POSTE_MA',N'Al Barid Bank (Poste Maroc)',    N'Al Barid Bank',          NULL)
) AS b(code, label_fr, label_en, swift_code)
WHERE p.iso_code = 'MA' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[banks] bk
      WHERE bk.pays_id = p.id AND bk.code = b.code
  );
GO

-- =============================================================================
-- VERIFICATION — counts per table
-- =============================================================================

SELECT 'grades'      AS tbl, pays_id, COUNT(*) AS cnt FROM [dbo].[grades]      GROUP BY pays_id
UNION ALL
SELECT 'disciplines',        pays_id, COUNT(*)        FROM [dbo].[disciplines]  GROUP BY pays_id
UNION ALL
SELECT 'nog_levels',         pays_id, COUNT(*)        FROM [dbo].[nog_levels]   GROUP BY pays_id
UNION ALL
SELECT 'departments',        pays_id, COUNT(*)        FROM [dbo].[departments]  GROUP BY pays_id
UNION ALL
SELECT 'banks',              pays_id, COUNT(*)        FROM [dbo].[banks]        GROUP BY pays_id
ORDER BY tbl, pays_id;
GO
