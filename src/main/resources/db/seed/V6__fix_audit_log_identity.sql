-- V6__fix_audit_log_identity.sql
-- The audit_log.id column lacks IDENTITY — recreate the table with it.
-- Safe to run multiple times (checks OBJECTPROPERTY first).

USE [DAF360_HR];

IF OBJECTPROPERTY(OBJECT_ID('dbo.audit_log'), 'TableHasIdentity') = 0
BEGIN
    PRINT 'audit_log.id has no IDENTITY — recreating table...';

    -- Preserve existing rows
    SELECT action, entity_id, entity_type, entityType, entityId,
           ip_address, ipAddress, module, new_value, newValue,
           old_value, oldValue, pays_id, status, [timestamp],
           user_id, userId
    INTO   [dbo].[audit_log_bak]
    FROM   [dbo].[audit_log];

    DROP TABLE [dbo].[audit_log];

    CREATE TABLE [dbo].[audit_log] (
        [id]          BIGINT        IDENTITY(1,1) NOT NULL,
        [userId]      VARCHAR(50)   NULL,
        [action]      VARCHAR(50)   NOT NULL,
        [entityType]  VARCHAR(100)  NULL,
        [entityId]    VARCHAR(100)  NULL,
        [oldValue]    TEXT          NULL,
        [newValue]    TEXT          NULL,
        [ipAddress]   VARCHAR(50)   NULL,
        [status]      VARCHAR(50)   NULL,
        [module]      VARCHAR(50)   NULL,
        [pays_id]     BIGINT        NULL,
        [timestamp]   DATETIMEOFFSET NULL,
        -- snake_case aliases (used by rh-service naming strategy)
        [entity_type] VARCHAR(100)  NULL,
        [entity_id]   VARCHAR(100)  NULL,
        [old_value]   TEXT          NULL,
        [new_value]   TEXT          NULL,
        [ip_address]  VARCHAR(50)   NULL,
        [user_id]     VARCHAR(50)   NULL,
        CONSTRAINT [PK_audit_log] PRIMARY KEY CLUSTERED ([id] ASC)
    );

    -- Restore data (best-effort — some columns may not map)
    INSERT INTO [dbo].[audit_log] (
        [userId],[action],[entityType],[entityId],[oldValue],[newValue],
        [ipAddress],[status],[module],[pays_id],[timestamp]
    )
    SELECT [userId],[action],[entityType],[entityId],[oldValue],[newValue],
           [ipAddress],[status],[module],[pays_id],[timestamp]
    FROM   [dbo].[audit_log_bak];

    DROP TABLE [dbo].[audit_log_bak];
    PRINT 'audit_log recreated with IDENTITY. Done.';
END
ELSE
    PRINT 'audit_log already has IDENTITY — no changes needed.';
