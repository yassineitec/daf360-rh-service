-- ─────────────────────────────────────────────────────────────────────────────
-- Types d'entretiens : libellé anglais facultatif (Admin › Types d'entretiens)
-- La fiche candidat (onglet Entretiens) affiche name_en quand l'interface est en
-- anglais, sinon name.
-- Idempotent. À appliquer à la main (pas de Flyway).
-- ─────────────────────────────────────────────────────────────────────────────
SET NOCOUNT ON;

IF COL_LENGTH('dbo.interview_types', 'name_en') IS NULL
    ALTER TABLE dbo.interview_types ADD name_en NVARCHAR(150) NULL;
