-- ============================================================================
--  MOCK DATA — Candidates in every workflow status (for /rh/candidates)
-- ----------------------------------------------------------------------------
--  Populates ~10 candidates per CandidateStatus, wiring the dependent rows each
--  status requires so the data is consistent with the real recruitment workflow:
--
--    PENDING         createCandidate()                     -> candidate only
--    REJECTED        rejectCandidate()                     -> + rejection_reason
--    ACCEPTED        acceptCandidate()                     -> + it_provisioning(PENDING), accepted_by/at
--    IT_IN_PROGRESS  IT starts provisioning                -> it_provisioning(IN_PROGRESS)
--    EMAIL_RECEIVED  IT creates MS365 account              -> it_provisioning(EMAIL_CREATED) + Users row (user_id)
--    HR_IN_PROGRESS  HR starts onboarding profile          -> it_provisioning(EMAIL_CREATED) + Users row
--    HIRED           hireCandidate()                       -> it_provisioning(COMPLETED) + Users row
--    ARCHIVED        terminal                              -> candidate only
--
--  Idempotent: every mock row is tagged with the '@mock.daf360.test' e-mail
--  domain and re-created on each run. Safe to run multiple times.
--  Self-resolving: no hard-coded IDs — pays / roles / employment types are
--  looked up by code so it runs on any environment (dev or test).
--
--  NOT a Flyway/ordered migration — run it manually against the target DB.
-- ============================================================================
SET NOCOUNT ON;
SET XACT_ABORT ON;
BEGIN TRAN;

-- ── Resolve environment references ──────────────────────────────────────────
DECLARE @paysId     BIGINT = (SELECT id FROM [dbo].[pays] WHERE french_label = N'Tunisie');
DECLARE @createdBy  BIGINT = COALESCE(
        (SELECT TOP 1 id FROM [dbo].[Users] WHERE pays_id = @paysId ORDER BY id),
        (SELECT TOP 1 id FROM [dbo].[Users] ORDER BY id));
DECLARE @collabRole BIGINT = (SELECT TOP 1 id FROM [dbo].[roles] WHERE frenchName = N'Collaborateur');

DECLARE @etCDI   BIGINT = (SELECT TOP 1 v.id FROM [dbo].[configurable_list_values] v
                             JOIN [dbo].[configurable_list_types] t ON t.id = v.list_type_id
                            WHERE t.code = 'EMPLOYMENT_TYPE' AND v.value_code = 'CDI');
DECLARE @etCDD   BIGINT = (SELECT TOP 1 v.id FROM [dbo].[configurable_list_values] v
                             JOIN [dbo].[configurable_list_types] t ON t.id = v.list_type_id
                            WHERE t.code = 'EMPLOYMENT_TYPE' AND v.value_code = 'CDD');
DECLARE @etSTAGE BIGINT = (SELECT TOP 1 v.id FROM [dbo].[configurable_list_values] v
                             JOIN [dbo].[configurable_list_types] t ON t.id = v.list_type_id
                            WHERE t.code = 'EMPLOYMENT_TYPE' AND v.value_code = 'STAGE');

IF @paysId IS NULL     SET @paysId = (SELECT TOP 1 id FROM [dbo].[pays] ORDER BY id);
IF @createdBy IS NULL
BEGIN
    RAISERROR('No Users found — cannot set candidates.created_by. Aborting.', 16, 1);
    ROLLBACK TRAN; RETURN;
END

-- ── Idempotent cleanup of any previous mock run ─────────────────────────────
DELETE ip
  FROM [dbo].[it_provisioning] ip
  JOIN [dbo].[candidates] c ON c.id = ip.candidate_id
 WHERE c.email_personal LIKE '%@mock.daf360.test';
DELETE FROM [dbo].[candidates] WHERE email_personal LIKE '%@mock.daf360.test';
DELETE FROM [dbo].[Users]      WHERE username LIKE 'u.mock.%@mock.daf360.test';

-- ── Reusable name / position pool ───────────────────────────────────────────
DECLARE @names TABLE (rn INT, fn NVARCHAR(100), ln NVARCHAR(100), position NVARCHAR(255));
INSERT INTO @names (rn, fn, ln, position) VALUES
 ( 1, N'Yassine',      N'Ben Salah', N'Ingénieur Génie Civil'),
 ( 2, N'Nour',         N'Trabelsi',  N'Technicienne BIM'),
 ( 3, N'Mohamed Amine',N'Gharbi',    N'Ingénieur Structures'),
 ( 4, N'Rania',        N'Belhadj',   N'Architecte'),
 ( 5, N'Skander',      N'Mansour',   N'Conducteur de Travaux'),
 ( 6, N'Ines',         N'Khelifi',   N'Ingénieure VRD'),
 ( 7, N'Achref',       N'Bouazizi',  N'Projeteur'),
 ( 8, N'Mariem',       N'Jlassi',    N'Ingénieure Hydraulique'),
 ( 9, N'Hamza',        N'Ferjani',   N'Dessinateur Projeteur'),
 (10, N'Sarra',        N'Chaabane',  N'Ingénieure QHSE'),
 (11, N'Bilel',        N'Aouadi',    N'Technicien Topographe'),
 (12, N'Emna',         N'Zouari',    N'Ingénieure Géotechnique');

-- ── Per-status configuration ────────────────────────────────────────────────
--  is_accepted -> accepted_by/at populated (candidate passed the PENDING gate)
--  age_days    -> how long ago the candidature was created (further = older)
DECLARE @cfg TABLE (status VARCHAR(30), cnt INT, is_accepted BIT, is_rejected BIT,
                    age_days INT, et BIGINT, ct VARCHAR(20));
INSERT INTO @cfg VALUES
 ('PENDING',        10, 0, 0,  5, @etCDI,   'PERMANENT'),
 ('ACCEPTED',       10, 1, 0, 20, @etCDI,   'PERMANENT'),
 ('IT_IN_PROGRESS', 10, 1, 0, 30, @etCDD,   'FIXED_TERM'),
 ('EMAIL_RECEIVED', 10, 1, 0, 40, @etCDD,   'FIXED_TERM'),
 ('HR_IN_PROGRESS', 10, 1, 0, 50, @etSTAGE, 'INTERN'),
 ('HIRED',          10, 1, 0, 70, @etCDI,   'PERMANENT'),
 ('REJECTED',       10, 0, 1, 15, @etCDI,   'PERMANENT'),
 ('ARCHIVED',       10, 0, 1, 90, @etCDD,   'FIXED_TERM');

-- ── 1) Candidates ───────────────────────────────────────────────────────────
INSERT INTO [dbo].[candidates]
 (pays_id, first_name, last_name, email_personal, phone, date_of_birth,
  applied_position, staff_type, contract_type, expected_start_date, status,
  rejection_reason, created_by, accepted_by, accepted_at, created_at, updated_at,
  notes, employment_type_id)
SELECT
  @paysId,
  n.fn, n.ln,
  'mock.' + LOWER(c.status) + '.' + CAST(n.rn AS VARCHAR(2)) + '@mock.daf360.test',
  '+2165' + RIGHT('000000' + CAST(n.rn * 13 AS VARCHAR(6)), 6),
  DATEADD(YEAR, -(24 + n.rn), CAST('2024-01-01' AS DATE)),
  n.position,
  'TECHNICAL',
  c.ct,
  DATEADD(DAY, 20 + n.rn, CAST(SYSDATETIMEOFFSET() AS DATE)),
  c.status,
  CASE WHEN c.is_rejected = 1 THEN N'Profil ne correspond pas aux exigences du poste' END,
  @createdBy,
  CASE WHEN c.is_accepted = 1 THEN @createdBy END,
  CASE WHEN c.is_accepted = 1 THEN DATEADD(DAY, -(c.age_days - 3), SYSDATETIMEOFFSET()) END,
  DATEADD(DAY, -c.age_days, SYSDATETIMEOFFSET()),
  SYSDATETIMEOFFSET(),
  N'Donnée de démonstration (mock)',
  c.et
FROM @cfg c
JOIN @names n ON n.rn <= c.cnt;

-- ── 2) MS365 user accounts (created by IT at the EMAIL step) ────────────────
--  Required from EMAIL_RECEIVED onward; hireCandidate() rejects a candidate
--  whose it_provisioning has no user_id.
INSERT INTO [dbo].[Users] (username, email, fullName, pays_id, role_id, isActive, created_at)
SELECT 'u.' + c.email_personal,
       'u.' + c.email_personal,
       c.first_name + ' ' + c.last_name,
       @paysId, @collabRole, 1, SYSDATETIMEOFFSET()
FROM [dbo].[candidates] c
WHERE c.email_personal LIKE '%@mock.daf360.test'
  AND c.status IN ('EMAIL_RECEIVED', 'HR_IN_PROGRESS', 'HIRED');

-- ── 3) IT provisioning (created at accept, advanced through the workflow) ────
INSERT INTO [dbo].[it_provisioning]
 (candidate_id, user_id, ms365_email, ms365_email_created_at,
  license_office365, license_autocad, license_revit, license_autodesk, license_kaspersky,
  ad_account_created, ad_account_created_at, status, completed_by, completed_at,
  created_at, updated_at, notes)
SELECT
  c.id,
  u.id,
  u.email,
  CASE WHEN c.status IN ('EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED') THEN SYSDATETIMEOFFSET() END,
  1, 0, 0, 0, 1,
  CASE WHEN c.status IN ('EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED') THEN 1 ELSE 0 END,
  CASE WHEN c.status IN ('EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED') THEN SYSDATETIMEOFFSET() END,
  CASE c.status
       WHEN 'ACCEPTED'       THEN 'PENDING'
       WHEN 'IT_IN_PROGRESS' THEN 'IN_PROGRESS'
       WHEN 'EMAIL_RECEIVED' THEN 'EMAIL_CREATED'
       WHEN 'HR_IN_PROGRESS' THEN 'EMAIL_CREATED'
       WHEN 'HIRED'          THEN 'COMPLETED'
  END,
  CASE WHEN c.status = 'HIRED' THEN @createdBy END,
  CASE WHEN c.status = 'HIRED' THEN SYSDATETIMEOFFSET() END,
  SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET(),
  N'Provisioning de démonstration (mock)'
FROM [dbo].[candidates] c
LEFT JOIN [dbo].[Users] u ON u.username = 'u.' + c.email_personal
WHERE c.email_personal LIKE '%@mock.daf360.test'
  AND c.status IN ('ACCEPTED','IT_IN_PROGRESS','EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED');

-- ── Report ──────────────────────────────────────────────────────────────────
SELECT status, COUNT(*) AS candidates
FROM [dbo].[candidates]
WHERE email_personal LIKE '%@mock.daf360.test'
GROUP BY status ORDER BY status;

COMMIT TRAN;
