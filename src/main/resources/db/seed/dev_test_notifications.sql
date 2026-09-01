-- =============================================================================
-- dev_test_notifications.sql
--
-- DEV/TEST TOOLKIT for the notification system. Not a migration — nothing here is
-- meant to run on production, and every block that writes is paired with a restore.
--
-- THE KEY INSIGHT THAT MAKES SINGLE-ACCOUNT TESTING WORK
-- -----------------------------------------------------------------------------
-- Two different things decide two different outcomes:
--
--   * WHO RECEIVES a notification is resolved at dispatch time, live from
--     [dbo].[Users].role_id / pays_id and [dbo].[RolePermissions].
--   * WHAT THE UI LETS YOU DO comes from the `permissions` claim inside your JWT,
--     which the portal mints at LOGIN and never re-reads.
--
-- So if you change your own role_id and DO NOT log out, you keep your admin
-- screens (old JWT) while receiving notifications as the new role (live DB). One
-- account, every role, no re-login. Reading the bell needs no permission at all —
-- /api/hr/notifications filters on your user id, not on a right.
--
-- Log out only when you WANT the new role's permissions; then remember you may
-- lose access to the admin screen and will have to restore your role from here.
-- =============================================================================

USE [DAF360_HR];

DECLARE @meId BIGINT = 207;   -- <<< your user id

-- ============================================================================
-- 1. WHO AM I
-- ============================================================================
SELECT u.id, COALESCE(u.username, u.email) AS login, u.fullName,
       u.role_id, r.frenchName AS role_name, r.parent_role_id,
       pr.frenchName AS parent_role_name, u.pays_id, u.isActive
FROM [dbo].[Users] u
LEFT JOIN [dbo].[Roles] r  ON r.id = u.role_id
LEFT JOIN [dbo].[Roles] pr ON pr.id = r.parent_role_id
WHERE u.id = @meId;

-- Roles you can hop to, with their hierarchy (the parent is what MANAGER mode targets).
SELECT r.id, r.frenchName, r.parent_role_id, pr.frenchName AS parent_name,
       (SELECT COUNT(*) FROM [dbo].[Users] u WHERE u.role_id = r.id AND u.isActive = 1) AS holders
FROM [dbo].[Roles] r
LEFT JOIN [dbo].[Roles] pr ON pr.id = r.parent_role_id
WHERE (r.deleted = 0 OR r.deleted IS NULL)
ORDER BY r.frenchName;

-- ============================================================================
-- 2. WILL THIS EVENT REACH ME?  ← the diagnostic worth running first
--
-- Replicates NotificationRoutingService's three modes against YOUR user, so you
-- know what to expect before triggering anything. `reaches_me = 0` on an event you
-- are trying to test means the rule does not point at you — change your role
-- (section 3) or add yourself as a recipient (section 4), not the code.
--
-- Caveat: assumes the event's entity is in YOUR pays. Recipients are pays-scoped,
-- so triggering an event on another entity's record reaches nobody here even when
-- this says 1.
-- ============================================================================
-- SQL Server rejects a subquery inside an aggregate, so the per-recipient match is
-- computed first in a CTE (joins, not subqueries) and only then aggregated.
WITH me AS (
    SELECT role_id, pays_id FROM [dbo].[Users] WHERE id = @meId
),
expanded AS (
    SELECT
        et.event_code,
        ru.id           AS rule_id,
        ru.send_inapp,
        ru.send_email,
        nr.id           AS recipient_id,
        CASE
            -- ALL: I hold the configured role
            WHEN nr.recipient_mode = 'ALL'
                 AND nr.role_id = me.role_id THEN 1
            -- MANAGER: my role IS the parent of the configured role
            WHEN nr.recipient_mode = 'MANAGER'
                 AND cr.parent_role_id = me.role_id THEN 1
            -- PERMISSION: my role carries the configured permission
            WHEN nr.recipient_mode = 'PERMISSION'
                 AND rp.permission IS NOT NULL THEN 1
            ELSE 0
        END AS hit
    FROM [dbo].[notification_event_types] et
    JOIN [dbo].[notification_routing_rules] ru
         ON ru.event_type_id = et.id AND ru.is_active = 1
    LEFT JOIN [dbo].[notification_routing_recipients] nr
         ON nr.routing_rule_id = ru.id AND nr.is_active = 1
    CROSS JOIN me
    -- the configured role, so its parent_role_id is reachable without a subquery
    LEFT JOIN [dbo].[Roles] cr
         ON cr.id = nr.role_id
    -- present only when I actually hold the configured permission
    LEFT JOIN [dbo].[RolePermissions] rp
         ON rp.role_id = me.role_id AND rp.permission = nr.permission_code
    WHERE et.is_active = 1
)
SELECT event_code, rule_id, send_inapp, send_email,
       COUNT(recipient_id) AS configured_recipients,
       MAX(hit)            AS reaches_me
FROM expanded
GROUP BY event_code, rule_id, send_inapp, send_email
ORDER BY reaches_me DESC, event_code;

-- Same question the other way round: for ONE event, WHO exactly is targeted.
-- Change @eventCode and re-run.
DECLARE @eventCode VARCHAR(100) = 'CANDIDATE_ACCEPTED';

SELECT nr.recipient_mode,
       nr.role_id,
       cr.frenchName AS configured_role,
       nr.permission_code,
       u.id          AS user_id,
       COALESCE(u.username, u.email) AS login,
       u.pays_id
FROM [dbo].[notification_event_types] et
JOIN [dbo].[notification_routing_rules] ru
     ON ru.event_type_id = et.id AND ru.is_active = 1
JOIN [dbo].[notification_routing_recipients] nr
     ON nr.routing_rule_id = ru.id AND nr.is_active = 1
LEFT JOIN [dbo].[Roles] cr ON cr.id = nr.role_id
LEFT JOIN [dbo].[Users] u
       ON (u.isActive = 1 OR u.isActive IS NULL)
      AND (
           (nr.recipient_mode = 'ALL'     AND u.role_id = nr.role_id)
        OR (nr.recipient_mode = 'MANAGER' AND u.role_id = cr.parent_role_id)
        OR (nr.recipient_mode = 'PERMISSION' AND EXISTS (
                SELECT 1 FROM [dbo].[RolePermissions] rp
                 WHERE rp.role_id = u.role_id AND rp.permission = nr.permission_code))
      )
WHERE et.event_code = @eventCode
ORDER BY nr.recipient_mode, u.id;

-- ============================================================================
-- 3. HOP TO ANOTHER ROLE  (no logout — see the header)
--
-- Run the SAVE, then the SWITCH, then trigger your event in the app, read your
-- bell, and RESTORE. Your JWT is untouched, so the admin screens keep working
-- throughout.
-- ============================================================================

-- 3a. SAVE — note the number this prints. It is your way back.
SELECT id, role_id AS role_id_to_restore, pays_id AS pays_id_to_restore
FROM [dbo].[Users] WHERE id = @meId;

-- 3b. SWITCH — set the role you want to receive as.
-- UPDATE [dbo].[Users] SET role_id = <target_role_id> WHERE id = 207;

-- 3c. Optional: hop entity too, to test the pays scoping.
-- UPDATE [dbo].[Users] SET pays_id = <target_pays_id> WHERE id = 207;

-- 3d. RESTORE — put back the values 3a printed. Do this before you next log out,
--     or you will log back in with the test role's permissions.
-- UPDATE [dbo].[Users] SET role_id = <saved>, pays_id = <saved> WHERE id = 207;

-- ============================================================================
-- 4. ALTERNATIVE WITH NO DB WRITES AT ALL  ← preferred
--
-- Because you are admin, the cleanest test is to point the RULE at yourself
-- instead of changing yourself:
--
--   /rh/admin → Notifications → pick the event → Destinataires in-app
--     → « Ajouter » → Cibler = « Tous les titulaires du rôle » → your own role
--
-- Trigger the event, read the bell, then remove the tag. Nothing in [Users]
-- changes, so there is nothing to restore and no way to lock yourself out.
-- Use « Tester la configuration » in the same screen to preview recipients and
-- rendered text without sending anything.
-- ============================================================================

-- ============================================================================
-- 5. WHAT TO DO IN THE APP TO FIRE EACH EVENT
-- ============================================================================
--   CANDIDATE_ACCEPTED            /rh/candidates → accept a candidate
--   IT_EMAIL_SUBMITTED            /rh/it-provisioning → submit the MS365 address
--   ONBOARDING_COMPLETED          /rh/onboarding → complete the file  (goes to the NEW EMPLOYEE only)
--   REQUEST_APPROVED / REJECTED   self-service request → decide it    (goes to the REQUESTER only)
--   RECRUITMENT_DEMAND_CREATED    /rh/recruitment-demands → create
--   RECRUITMENT_DEMAND_APPROVED   /rh/recruitment-demands → approve
--   OFFBOARDING_STARTED           /rh/offboarding → start a departure
--   OFFBOARDING_MANAGER_OPINION   /rh/offboarding/:id → record the manager opinion
--   EMPLOYEE_STATUS_CHANGED       contract status transition (e.g. ACTIVE → NOTICE_PERIOD)
--
-- The four scheduled ones cannot be triggered from the UI. They run daily at 08h00
-- (CONTRACT_EXPIRY) and 08h05 (the three offboarding alerts). To test them now,
-- either restart the service with the cron temporarily shortened, or make an
-- offboarding task overdue by back-dating its due date and wait for 08h05.
--
-- NOTE on the two directUserId events: ONBOARDING_COMPLETED and REQUEST_* ignore
-- the rule's recipients entirely — they always go to the person concerned. Adding
-- yourself as a recipient will NOT make them reach you; you have to be that person.

-- ============================================================================
-- 6. FAKE ONE, TO CHECK THE UI ONLY
--
-- Bypasses the whole engine. Good for eyeballing the bell, the unread rail, the
-- module chip and the deep link; proves nothing about routing.
-- ============================================================================
DECLARE @before BIGINT = ISNULL((SELECT MAX(id) FROM [dbo].[notifications]), 0);

INSERT INTO [dbo].[notifications]
    (user_id, module, title, message, is_read, created_at, entity_type, entity_id, link)
VALUES
 (@meId, 'RH', N'Test — notification non lue',
  N'Vérification de l''affichage : liseré non lu, puce de module et lien.',
  0, SYSDATETIMEOFFSET(), 'CANDIDATE', 482, NULL),
 (@meId, 'RH', N'Test — notification lue',
  N'Doit apparaître sans liseré et sans titre en gras.',
  1, DATEADD(DAY, -1, SYSDATETIMEOFFSET()), NULL, NULL, NULL);

SELECT @before AS delete_rows_with_id_greater_than;

-- ============================================================================
-- 7. VERIFY — what actually landed
-- ============================================================================
SELECT TOP 20 id, module, title, is_read, entity_type, entity_id, created_at
FROM [dbo].[notifications]
WHERE user_id = @meId
ORDER BY created_at DESC;

-- What GET /api/hr/notifications/unread-count should answer for you.
SELECT COUNT(*) AS unread_total FROM [dbo].[notifications]
WHERE user_id = @meId AND is_read = 0;

SELECT module, COUNT(*) AS unread FROM [dbo].[notifications]
WHERE user_id = @meId AND is_read = 0 GROUP BY module;

-- Nothing arrived? Check the service log for these two lines before suspecting the
-- code — they are the two silent-failure cases, and both now say so out loud:
--   "No routing rule found for event=... pays=..."
--   "No in-app recipients resolved for event=... pays=... rule=..."

-- ============================================================================
-- 8. CLEANUP
-- ============================================================================
-- DELETE FROM [dbo].[notifications] WHERE user_id = 207 AND id > <watermark from 6>;
-- and don't forget 3d if you switched roles.
