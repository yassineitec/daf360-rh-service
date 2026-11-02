-- V103__backfill_contract_types_baseline.sql
-- Environment-drift fix found 2026-10-22 while investigating an unrelated Tunisia
-- payroll question: `contract_types` (V102, the real per-country reference list the
-- UI dropdowns and daf360-payroll-service's getContractTypes(paysId) read from) was
-- never actually applied on 192.168.1.233 -- the table didn't exist there at all,
-- while localhost had it fully seeded. This migration brings an environment missing
-- V102 up to the same baseline, idempotently, without assuming which gap (if any)
-- it currently has.
--
-- (An earlier version of this file also added a new 'SIVP' contract type for
-- Tunisia -- reverted the same day: "SIVP" in the source questionnaire turned out
-- to be the old name for the already-existing CIVP, not a separate type. See
-- daf360-payroll-service's V25__tunisia_parameter_set_2026.sql for where that
-- correction actually belongs.)

IF OBJECT_ID(N'[dbo].[contract_types]', N'U') IS NULL
BEGIN
    CREATE TABLE contract_types (
        id            BIGINT IDENTITY(1,1) PRIMARY KEY,
        pays_id       BIGINT NOT NULL,
        code          NVARCHAR(30) NOT NULL,
        label_fr      NVARCHAR(100) NOT NULL,
        label_en      NVARCHAR(100) NOT NULL,
        display_order INT NOT NULL DEFAULT 0,
        is_active     BIT NOT NULL DEFAULT 1,
        created_at    DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT UQ_contract_types_pays_code UNIQUE (pays_id, code)
    );
    CREATE INDEX IX_contract_types_pays_active ON contract_types(pays_id, is_active);
END
GO

-- Exact same rows as V102, idempotent.
IF NOT EXISTS (SELECT 1 FROM contract_types WHERE pays_id = 179 AND code = 'CDI')
BEGIN
    INSERT INTO contract_types (pays_id, code, label_fr, label_en, display_order) VALUES
    (179, 'CDI',         N'CDI',                  'Permanent contract',   1),
    (179, 'CDD',         N'CDD',                  'Fixed-term contract',  2),
    (179, 'CIVP',        N'CIVP',                 'CIVP (Tunisia)',       3),
    (179, 'STAGE',       N'Stage',                'Internship',           4),
    (179, 'FREELANCE',   N'Freelance',            'Freelance',            5),
    (179, 'DETACHEMENT', N'Détachement',          'Secondment',           6),
    (179, 'PORTAGE',     N'Portage salarial',     'Umbrella employment',  7),
    (179, 'PERMANENT',   N'Permanent',            'Permanent',            8),
    (179, 'FIXED_TERM',  N'Durée déterminée',     'Fixed term',           9),
    (179, 'INTERN',      N'Stagiaire',            'Intern',              10),
    (179, 'CONSULTANT',  N'Consultant',           'Consultant',          11);
END
IF NOT EXISTS (SELECT 1 FROM contract_types WHERE pays_id = 53 AND code = 'CDI')
BEGIN
    INSERT INTO contract_types (pays_id, code, label_fr, label_en, display_order) VALUES
    (53, 'CDI',         N'CDI',                  'Permanent contract',   1),
    (53, 'CDD',         N'CDD',                  'Fixed-term contract',  2),
    (53, 'STAGE',       N'Stage',                'Internship',           3),
    (53, 'FREELANCE',   N'Freelance',            'Freelance',            4),
    (53, 'DETACHEMENT', N'Détachement',          'Secondment',           5),
    (53, 'PORTAGE',     N'Portage salarial',     'Umbrella employment',  6),
    (53, 'PERMANENT',   N'Permanent',            'Permanent',            7),
    (53, 'FIXED_TERM',  N'Durée déterminée',     'Fixed term',           8),
    (53, 'INTERN',      N'Stagiaire',            'Intern',               9),
    (53, 'CONSULTANT',  N'Consultant',           'Consultant',          10);
END
GO
