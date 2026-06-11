-- =============================================================================
-- Payroll parameter_sets seed — DAF360_HR
-- Reference file for manual seeding or disaster recovery.
-- The application seeds these automatically on first run via ParameterSetService.seedDefaults().
--
-- Table: parameter_sets (pays_id, cle, valeur, description, updated_at)
-- Verified schema 2026-05-31.
--
-- Fiscal year: 2024-2025  |  Source: Tunisian tax authority + EG placeholder
-- =============================================================================

USE [DAF360_HR];

-- ─────────────────────────────────────────────────────────────────────────────
-- TUNISIE (TN) — find pays_id dynamically
-- ─────────────────────────────────────────────────────────────────────────────
DECLARE @tn BIGINT = (SELECT id FROM [dbo].[pays] WHERE iso_code = 'TN');

IF @tn IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id = @tn AND cle = 'TAUX_CNSS_EMPLOYE')
BEGIN
    INSERT INTO [dbo].[parameter_sets] (pays_id, cle, valeur, description, updated_at)
    VALUES
    (@tn, 'TAUX_CNSS_EMPLOYE',  '9.18',    'Cotisation CNSS salarié (%)',               SYSDATETIMEOFFSET()),
    (@tn, 'TAUX_CNSS_PATRONAL', '16.57',   'Cotisation CNSS patronale (%)',             SYSDATETIMEOFFSET()),
    (@tn, 'TAUX_CSS',           '1.0',     'Contribution Sociale de Solidarité (%)',    SYSDATETIMEOFFSET()),
    (@tn, 'TAUX_RAMT',          '0.5',     'Régime AT/MP salarié (%)',                  SYSDATETIMEOFFSET()),
    (@tn, 'NB_MOIS_PAIE',       '12',      'Nombre de mois de paie par an',             SYSDATETIMEOFFSET()),
    (@tn, 'DEVISE',             'TND',     'Devise ISO 4217',                           SYSDATETIMEOFFSET()),
    (@tn, 'IRPP_BRACKETS',
     '[{"from":0,"to":5000,"rate":0},{"from":5000,"to":10000,"rate":26},{"from":10000,"to":20000,"rate":28},{"from":20000,"to":30000,"rate":32},{"from":30000,"to":50000,"rate":34},{"from":50000,"rate":35}]',
     'Tranches IRPP annuelles (JSON) — exercice 2024',
     SYSDATETIMEOFFSET());

    PRINT 'TN parameters seeded (' + CAST(@tn AS VARCHAR) + ')';
END
ELSE
    PRINT 'TN parameters already present or pays not found';

-- ─────────────────────────────────────────────────────────────────────────────
-- ÉGYPTE (EG) — placeholder values
-- ─────────────────────────────────────────────────────────────────────────────
DECLARE @eg BIGINT = (SELECT id FROM [dbo].[pays] WHERE iso_code = 'EG');

IF @eg IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[parameter_sets] WHERE pays_id = @eg AND cle = 'TAUX_CNSS_EMPLOYE')
BEGIN
    INSERT INTO [dbo].[parameter_sets] (pays_id, cle, valeur, description, updated_at)
    VALUES
    (@eg, 'TAUX_CNSS_EMPLOYE',  '11.0',    'Social Insurance employee (%)',             SYSDATETIMEOFFSET()),
    (@eg, 'TAUX_CNSS_PATRONAL', '18.75',   'Social Insurance employer (%)',             SYSDATETIMEOFFSET()),
    (@eg, 'TAUX_CSS',           '0.0',     'No solidarity tax (placeholder)',           SYSDATETIMEOFFSET()),
    (@eg, 'NB_MOIS_PAIE',       '12',      'Months per year',                           SYSDATETIMEOFFSET()),
    (@eg, 'DEVISE',             'EGP',     'Egyptian Pound ISO 4217',                  SYSDATETIMEOFFSET()),
    (@eg, 'IRPP_BRACKETS',
     '[{"from":0,"to":15000,"rate":0},{"from":15000,"to":30000,"rate":10},{"from":30000,"to":45000,"rate":15},{"from":45000,"to":60000,"rate":20},{"from":60000,"to":200000,"rate":22.5},{"from":200000,"rate":25}]',
     'Income Tax annual brackets (JSON) — placeholder 2024',
     SYSDATETIMEOFFSET());

    PRINT 'EG parameters seeded (' + CAST(@eg AS VARCHAR) + ')';
END
ELSE
    PRINT 'EG parameters already present or pays not found';

-- Verify
SELECT pays_id, cle, valeur, updated_at
FROM [dbo].[parameter_sets]
WHERE pays_id IN (@tn, @eg)
ORDER BY pays_id, cle;
