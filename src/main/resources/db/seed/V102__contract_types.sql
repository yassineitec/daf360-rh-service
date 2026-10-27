-- V102__contract_types.sql
-- Per-country contract-type reference list, replacing four independently hardcoded
-- lists across rh-service/rh-frontend/payroll-service/payroll-frontend. See
-- docs/superpowers/specs/2026-09-24-contract-types-reference-design.md.

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

-- Tunisia (179) — all 11 known codes across both onboarding-wizard generations and
-- legacy employee_profiles.contract_type values.
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

-- Egypt (53) — same list minus CIVP (Tunisia-specific ANETI scheme, see design doc).
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
