-- V98 — Fix "Attestation de Salaire": stop claiming a "Salaire Annuel Brut" (gross salary).
--
-- There is no gross-salary field anywhere in the schema (V24 explicitly dropped payroll
-- simulation tables; employee_profiles only stores salaire_net_rh/salaire_net_candidat,
-- both net). Both rendering paths (PdfDocumentService.generateAttestationSalairePdf and
-- DocumentTemplateService.resolveContext) compute salaire_net_rh * 12 and, until now,
-- wrote that identical figure into BOTH employee.salaireBrutAnnuel and
-- employee.salaireNetAnnuel -- every issued certificate stated a "gross" amount that was
-- numerically identical to the net amount, which is factually wrong. Decision: state only
-- the real net salary (employee.salaireNetAnnuel) and drop the false "Brut" claim from the
-- document text. employee.salaireBrutAnnuel / employee.salaireBrutAnnuelEnLettres keep
-- being populated in Java for backward compatibility with any custom template that
-- already references them, but the standard wording no longer uses "Brut".
--
-- Three distinct html_content wordings exist across the [dbo].[document_templates] rows
-- for name = 'Attestation de Salaire' (one row per pays, seeded by V47/V48/V49): the
-- Tunisia-specific redesign, the Egypt English translation, and the original generic
-- French wording still present on every other (currently 0-employee) pays. REPLACE() is a
-- no-op on rows that don't contain the exact search text, so it's safe to run all three
-- against every row.

USE [DAF360_HR];
GO

-- ── 1. Tunisia-specific wording (accented, "Ceci est l'equivalent..." second sentence) ──
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        [html_content],
        N'perçoit un Salaire Annuel Brut estimé à <strong>{{employee.salaireBrutAnnuel}} Dinars Tunisiens (TND)</strong> soit <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>. Ceci est l''équivalent d''un Salaire Annuel Net De <strong>{{employee.salaireNetAnnuel}} Dinars Tunisiens (TND)</strong>.',
        N'perçoit un Salaire Annuel Net estimé à <strong>{{employee.salaireNetAnnuel}} Dinars Tunisiens (TND)</strong> soit <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>.'
    ),
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Attestation de Salaire'
  AND [html_content] LIKE N'%Salaire Annuel Brut estimé%';
GO

-- ── 2. Egypt English wording ──────────────────────────────────────────────────
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        [html_content],
        N'receives an estimated Annual Gross Salary of <strong>{{employee.salaireBrutAnnuel}} Egyptian Pounds (EGP)</strong>, i.e. <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>. This is equivalent to an Annual Net Salary of <strong>{{employee.salaireNetAnnuel}} Egyptian Pounds (EGP)</strong>.',
        N'receives an estimated Annual Net Salary of <strong>{{employee.salaireNetAnnuel}} Egyptian Pounds (EGP)</strong>, i.e. <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>.'
    ),
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Attestation de Salaire'
  AND [html_content] LIKE N'%estimated Annual Gross Salary%';
GO

-- ── 3. Original generic French wording (no accents, single sentence, no Net figure) ──
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        [html_content],
        N'touche un salaire brut annuel de <strong>{{employee.salaireBrutAnnuel}} TND ({{employee.salaireBrutAnnuelEnLettres}} millimes)</strong>.',
        N'touche un salaire net annuel de <strong>{{employee.salaireNetAnnuel}} TND ({{employee.salaireBrutAnnuelEnLettres}} millimes)</strong>.'
    ),
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Attestation de Salaire'
  AND [html_content] LIKE N'%touche un salaire brut annuel de%';
GO
