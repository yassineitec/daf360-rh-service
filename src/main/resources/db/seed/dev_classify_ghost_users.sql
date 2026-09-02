-- =============================================================================
-- dev_classify_ghost_users.sql
--
-- Proposes which [dbo].[Users] rows are NOT people, so is_employee can be cleared.
-- Every UPDATE is commented out: this script decides nothing. Misclassifying a
-- real colleague removes them from every picker in the application, so a human
-- reads the candidates first.
--
-- Run V96 before this (V94's account_type has been replaced by the binary is_employee).
--
-- WHY NOT "no employee profile"
-- -----------------------------------------------------------------------------
-- Because it is not a signal. 155 active users have no employee_profiles row and
-- nearly all are real — the DRH, the PDG, the IT lead, two HR assistants, ~25
-- site managers, ~100 collaborators. Their HR file is merely incomplete. That is
-- why the flag lives on the ACCOUNT and why this script uses different evidence.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- 1. THE STRONGEST SIGNAL: no Azure identity
--
-- Everyone signs in through Azure AD, so a real account carries an azure_oid.
-- A row without one was created by a script or by hand and has never been — and
-- often cannot be — logged into.
--
-- Read the `verdict` column as a PROPOSAL, not a decision.
-- ============================================================================
SELECT u.id,
       u.fullName,
       r.frenchName AS role,
       u.pays_id,
       u.username,
       u.email,
       u.employee_id,
       CASE WHEN u.azure_oid IS NULL THEN 'no azure identity' ELSE '' END        AS no_sso,
       COUNT(*) OVER (PARTITION BY LOWER(LTRIM(RTRIM(u.fullName))))              AS namesakes,
       CASE
           WHEN LOWER(u.fullName) LIKE '%test%'                        THEN 'NOT A PERSON — name says so'
           WHEN LOWER(u.fullName) LIKE '%john doe%'                     THEN 'NOT A PERSON — placeholder name'
           WHEN LOWER(u.fullName) LIKE 'timesheet%'                     THEN 'NOT A PERSON — integration account'
           WHEN u.azure_oid IS NULL
                AND COUNT(*) OVER (PARTITION BY LOWER(LTRIM(RTRIM(u.fullName)))) > 1
                                                                        THEN 'NOT A PERSON — duplicate, no SSO'
           WHEN u.azure_oid IS NULL                                     THEN 'REVIEW — no SSO'
           ELSE 'keep — real person'
       END                                                                       AS verdict
FROM [dbo].[Users] u
LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id
WHERE (u.isActive = 1 OR u.isActive IS NULL)
  AND u.is_employee = 1
ORDER BY CASE WHEN u.azure_oid IS NULL THEN 0 ELSE 1 END,
         namesakes DESC,
         u.fullName;

-- ============================================================================
-- 2. DUPLICATES — the same person imported several times
--
-- Ids 10287–10313 in the sample carried names three times over, which is the
-- shape of a re-run import rather than of namesakes. Keep the row that has an
-- azure_oid or an employee_id; the others are the copies.
-- ============================================================================
SELECT LOWER(LTRIM(RTRIM(u.fullName))) AS name_key,
       COUNT(*)                        AS copies,
       STRING_AGG(CAST(u.id AS VARCHAR(20)), ', ') WITHIN GROUP (ORDER BY u.id) AS ids,
       SUM(CASE WHEN u.azure_oid  IS NOT NULL THEN 1 ELSE 0 END) AS with_sso,
       SUM(CASE WHEN u.employee_id IS NOT NULL THEN 1 ELSE 0 END) AS with_matricule
FROM [dbo].[Users] u
WHERE (u.isActive = 1 OR u.isActive IS NULL)
GROUP BY LOWER(LTRIM(RTRIM(u.fullName)))
HAVING COUNT(*) > 1
ORDER BY copies DESC, name_key;

-- ============================================================================
-- 3. WHAT AN EXCLUSION WOULD COST — run this BEFORE any UPDATE
--
-- For each candidate: does anything actually depend on them? A row that owns
-- missions, interviews or an HR profile is almost certainly a real person, and
-- flagging it would break existing records rather than tidy a list.
-- ============================================================================
SELECT u.id, u.fullName,
       (SELECT COUNT(*) FROM [dbo].[employee_profiles] ep
         WHERE ep.user_id = u.id AND ep.deleted = 0)                  AS hr_profiles,
       (SELECT COUNT(*) FROM [dbo].[missions] m
         WHERE m.employee_user_id = u.id OR m.responsable_user_id = u.id) AS missions,
       (SELECT COUNT(*) FROM [dbo].[notifications] n
         WHERE n.user_id = u.id)                                      AS notifications
FROM [dbo].[Users] u
WHERE (u.isActive = 1 OR u.isActive IS NULL)
  AND u.azure_oid IS NULL
ORDER BY hr_profiles DESC, missions DESC, u.fullName;

-- ============================================================================
-- 4. APPLY — uncomment ONLY the ids you have decided on
--
-- Explicit id lists, never a LIKE. A pattern that matches 'test' today matches
-- a real "Testour" tomorrow, and this UPDATE removes people from the whole app.
-- ============================================================================

-- One statement, one flag. Test logins, duplicated imports AND integration accounts all
-- become is_employee = 0: they stop appearing in every list of people, while staying
-- perfectly able to sign in and to be mirrored to the other services.
-- UPDATE [dbo].[Users] SET is_employee = 0
--  WHERE id IN ( /* e.g. 20224, 30226, 10287, 10288, ... and the TimeSheet accounts */ );

-- ── Verify after applying ────────────────────────────────────────────────────
SELECT is_employee, COUNT(*) AS nb FROM [dbo].[Users] GROUP BY is_employee;

-- Sanity check: these people MUST still be is_employee = 1, or a picker just lost its
-- management. Adjust the roles to your own vocabulary.
SELECT u.id, u.fullName, r.frenchName AS role, u.is_employee
FROM [dbo].[Users] u
JOIN [dbo].[Roles] r ON r.id = u.role_id
WHERE r.frenchName IN (
        'Directeur des Ressources Humaines (DRH)', 'PDG', 'IT',
        'Responsable IT (Tunisie)', 'Assistante RH', 'Ressources Humaines (RH)')
ORDER BY r.frenchName, u.fullName;
