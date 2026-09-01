-- =============================================================================
-- V90__notification_deep_links.sql
--
-- Makes in-app notifications clickable and consistent, ahead of wiring the shell
-- header's notification panel.
--
-- 1. baseline [dbo].[notifications] so a fresh environment can be built from the repo
--    (the table was created by hand on 2026-06-17 and only ever ALTERed by V11)
-- 2. deep-link columns: entity_type / entity_id / link
-- 3. IX_Notif_User_Created — the panel's list query has no covering index today
-- 4. notification_event_types.default_entity_type
-- 5. fold the 'HR' module spelling into 'RH'
--
-- Safe to re-run: every block is guarded. Never drops or renames anything.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- 1. BASELINE — [dbo].[notifications]
-- No-op on every existing environment. Present so the schema is reproducible
-- from source: this table has never had a CREATE statement in the repo.
-- ============================================================================

IF OBJECT_ID('dbo.notifications','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[notifications] (
        [id]         BIGINT IDENTITY(1,1) NOT NULL,
        [user_id]    BIGINT               NOT NULL,
        [module]     VARCHAR(50)          NOT NULL,
        [title]      NVARCHAR(255)        NOT NULL,
        [message]    NVARCHAR(1000)       NOT NULL,
        [is_read]    BIT                  NOT NULL DEFAULT (0),
        [created_at] DATETIMEOFFSET(6)    NOT NULL DEFAULT (SYSDATETIMEOFFSET()),
        [read_at]    DATETIMEOFFSET(6)    NULL,
        CONSTRAINT [PK_notifications] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_Notif_User] FOREIGN KEY ([user_id])
            REFERENCES [dbo].[Users]([id])
    );
    CREATE NONCLUSTERED INDEX [IX_Notif_User_Unread]
        ON [dbo].[notifications] ([user_id], [is_read]);
    PRINT 'Created: notifications (baseline)';
END
ELSE PRINT 'Skipped: notifications (already exists)';

-- ============================================================================
-- 2. DEEP-LINK COLUMNS
--
-- entity_type + entity_id, not a stored route: a route string would couple this
-- service to the Angular router and break for good the day a path is renamed,
-- whereas a kind + id is resolved at click time, so a rename fixes every
-- historical row at once. `link` is the escape hatch for targets no entity kind
-- describes (a pre-filtered list, an external URL).
--
-- All three stay NULL-able. Rows written before this migration have no target
-- and must stay non-clickable — there is nothing to backfill them from.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='notifications' AND COLUMN_NAME='entity_type')
BEGIN
    ALTER TABLE [dbo].[notifications] ADD [entity_type] VARCHAR(50) NULL;
    PRINT 'Added: notifications.entity_type';
END
ELSE PRINT 'Skipped: notifications.entity_type (already exists)';

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='notifications' AND COLUMN_NAME='entity_id')
BEGIN
    ALTER TABLE [dbo].[notifications] ADD [entity_id] BIGINT NULL;
    PRINT 'Added: notifications.entity_id';
END
ELSE PRINT 'Skipped: notifications.entity_id (already exists)';

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='notifications' AND COLUMN_NAME='link')
BEGIN
    ALTER TABLE [dbo].[notifications] ADD [link] NVARCHAR(500) NULL;
    PRINT 'Added: notifications.link';
END
ELSE PRINT 'Skipped: notifications.link (already exists)';

-- Deliberately NO check constraint on entity_type. The values are an application
-- enum (NotificationEntityType) that will grow as modules are integrated, and an
-- unknown value must degrade to "not clickable" in the UI rather than block the
-- INSERT of an otherwise valid notification.

-- ============================================================================
-- 3. INDEX FOR THE PANEL QUERY
--
-- IX_Notif_User_Unread (user_id, is_read) already covers the unread count, but
-- the list query is
--     WHERE user_id = ? ORDER BY created_at DESC OFFSET 0 FETCH NEXT 50
-- which has to sort today. INCLUDE stays at (is_read) on purpose: pulling
-- message NVARCHAR(1000) into the leaf pages to save 50 key lookups per panel
-- open is a bad trade.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'IX_Notif_User_Created'
                 AND object_id = OBJECT_ID('dbo.notifications'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Notif_User_Created]
        ON [dbo].[notifications] ([user_id] ASC, [created_at] DESC)
        INCLUDE ([is_read]);
    PRINT 'Created: IX_Notif_User_Created';
END
ELSE PRINT 'Skipped: IX_Notif_User_Created (already exists)';

-- ============================================================================
-- 4. notification_event_types.default_entity_type
--
-- The kind an event always points at, so a routing call site only has to supply
-- the id. NULL means "no deep link" and that is a deliberate choice for three of
-- the eight events, not an omission — see the seed below.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='notification_event_types'
                 AND COLUMN_NAME='default_entity_type')
BEGIN
    ALTER TABLE [dbo].[notification_event_types] ADD [default_entity_type] VARCHAR(50) NULL;
    PRINT 'Added: notification_event_types.default_entity_type';
END
ELSE PRINT 'Skipped: notification_event_types.default_entity_type (already exists)';
GO

-- Recipients of these two are HR/IT staff, who can open the target pages.
UPDATE [dbo].[notification_event_types]
   SET [default_entity_type] = 'CANDIDATE'
 WHERE [event_code] = 'CANDIDATE_ACCEPTED' AND [default_entity_type] IS NULL;

UPDATE [dbo].[notification_event_types]
   SET [default_entity_type] = 'IT_PROVISIONING'
 WHERE [event_code] = 'IT_EMAIL_SUBMITTED' AND [default_entity_type] IS NULL;

-- ONBOARDING_COMPLETED, REQUEST_APPROVED and REQUEST_REJECTED stay NULL.
-- Their recipient is the employee concerned, and every RH page that would show
-- the underlying record is permission-guarded (HR_ONBOARDING, HR_UPDATE_PROFILE)
-- for exactly the staff roles that employee does not hold. A link that lands on
-- /forbidden is worse than no link, so these stay non-clickable until the
-- self-service equivalents exist.
--
-- LEAVE_SUBMITTED / LEAVE_APPROVED / LEAVE_REJECTED also stay NULL: no code
-- dispatches them (leave requests are not written by this service), so their
-- rules are configurable but dormant.

-- ============================================================================
-- 5. MODULE SPELLING: 'HR' -> 'RH'
--
-- The routing engine writes notification_event_types.module, seeded as 'HR',
-- while every hand-written producer wrote 'RH' — the same module under two
-- spellings, in one table. The frontend keys its module chip and its filters off
-- this column, so the two would show up as two different modules.
--
-- Only ever touches rows this service wrote: 'HR' was never used by anything
-- else. InAppNotifier.normalizeModule keeps new rows consistent.
-- ============================================================================

UPDATE [dbo].[notifications]
   SET [module] = 'RH'
 WHERE [module] = 'HR';
PRINT 'Normalised notifications.module: HR -> RH';

UPDATE [dbo].[notification_event_types]
   SET [module] = 'RH'
 WHERE [module] = 'HR';
PRINT 'Normalised notification_event_types.module: HR -> RH';

PRINT 'V90 complete.';
