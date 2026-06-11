-- =============================================================================
-- V2 — Onboarding schema additions to DAF360_HR
-- Applied: 2026-06-01 (verified against live DB before execution)
-- Author:  DAF360 team
--
-- Scope — only what was MISSING from the database at time of execution:
--   NEW tables:    candidates, it_provisioning, onboarding_drafts
--   ALTER:         portal_events  (+5 cols)
--   ALTER:         employee_profiles (+8 cols, +1 FK)
--   SKIPPED:       Users (all 5 target cols already existed)
--   SKIPPED:       audit_log (both target cols already existed)
--   SKIPPED:       Roles / RolePermissions — owned by Timesheet app, do not touch
-- =============================================================================

USE [DAF360_HR];

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. candidates
-- ─────────────────────────────────────────────────────────────────────────────
IF OBJECT_ID('dbo.candidates', 'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[candidates] (
        [id]                    BIGINT IDENTITY(1,1)  NOT NULL,
        [pays_id]               BIGINT                NOT NULL,
        [first_name]            NVARCHAR(100)         NOT NULL,
        [last_name]             NVARCHAR(100)         NOT NULL,
        [email_personal]        VARCHAR(255)          NOT NULL,
        [phone]                 VARCHAR(50)           NULL,
        [date_of_birth]         DATE                  NULL,
        [nationality]           VARCHAR(100)          NULL,
        [national_id]           VARCHAR(100)          NULL,
        [applied_position]      NVARCHAR(255)         NULL,
        [applied_grade]         VARCHAR(100)          NULL,
        [applied_discipline]    VARCHAR(100)          NULL,
        [staff_type]            VARCHAR(30)           NULL,
        [contract_type]         VARCHAR(50)           NULL,
        [expected_start_date]   DATE                  NULL,
        [status]                VARCHAR(30)           NOT NULL DEFAULT 'PENDING',
        [rejection_reason]      NVARCHAR(500)         NULL,
        [created_by]            BIGINT                NOT NULL,
        [accepted_by]           BIGINT                NULL,
        [accepted_at]           DATETIMEOFFSET(6)     NULL,
        [created_at]            DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]            DATETIMEOFFSET(6)     NULL,
        [notes]                 NVARCHAR(1000)        NULL,
        CONSTRAINT [PK_candidates]             PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_Candidate_Pays]         FOREIGN KEY ([pays_id])
            REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_Candidate_CreatedBy]    FOREIGN KEY ([created_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [FK_Candidate_AcceptedBy]   FOREIGN KEY ([accepted_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_Candidate_Status]       CHECK ([status] IN (
            'PENDING','ACCEPTED','REJECTED','IT_IN_PROGRESS',
            'EMAIL_RECEIVED','HR_IN_PROGRESS','HIRED','ARCHIVED')),
        CONSTRAINT [CK_Candidate_StaffType]    CHECK ([staff_type] IN (
            'TECHNICAL','OPERATIONS_SUPPORT')),
        CONSTRAINT [CK_Candidate_ContractType] CHECK ([contract_type] IN (
            'PERMANENT','FIXED_TERM','INTERN','CONSULTANT'))
    );
    PRINT 'Created: candidates';
END
ELSE PRINT 'Skipped: candidates (already exists)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. it_provisioning
-- ─────────────────────────────────────────────────────────────────────────────
IF OBJECT_ID('dbo.it_provisioning', 'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[it_provisioning] (
        [id]                        BIGINT IDENTITY(1,1)  NOT NULL,
        [candidate_id]              BIGINT                NOT NULL,
        [user_id]                   BIGINT                NULL,
        [ms365_email]               VARCHAR(255)          NULL,
        [ms365_email_created_at]    DATETIMEOFFSET(6)     NULL,
        [laptop_provided]           BIT                   NOT NULL DEFAULT 0,
        [mouse_provided]            BIT                   NOT NULL DEFAULT 0,
        [keyboard_provided]         BIT                   NOT NULL DEFAULT 0,
        [screen_provided]           BIT                   NOT NULL DEFAULT 0,
        [headset_provided]          BIT                   NOT NULL DEFAULT 0,
        [docking_station_provided]  BIT                   NOT NULL DEFAULT 0,
        [hardware_notes]            NVARCHAR(500)         NULL,
        [license_office365]         BIT                   NOT NULL DEFAULT 0,
        [license_autocad]           BIT                   NOT NULL DEFAULT 0,
        [license_revit]             BIT                   NOT NULL DEFAULT 0,
        [license_autodesk]          BIT                   NOT NULL DEFAULT 0,
        [license_kaspersky]         BIT                   NOT NULL DEFAULT 0,
        [license_other]             NVARCHAR(255)         NULL,
        [ad_account_created]        BIT                   NOT NULL DEFAULT 0,
        [ad_profile_type]           NVARCHAR(100)         NULL,
        [ad_account_created_at]     DATETIMEOFFSET(6)     NULL,
        [status]                    VARCHAR(30)           NOT NULL DEFAULT 'PENDING',
        [completed_by]              BIGINT                NULL,
        [completed_at]              DATETIMEOFFSET(6)     NULL,
        [notes]                     NVARCHAR(1000)        NULL,
        [created_at]                DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]                DATETIMEOFFSET(6)     NULL,
        CONSTRAINT [PK_it_provisioning]      PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_ITProv_Candidate]     FOREIGN KEY ([candidate_id])
            REFERENCES [dbo].[candidates]([id]),
        CONSTRAINT [FK_ITProv_User]          FOREIGN KEY ([user_id])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [FK_ITProv_CompletedBy]   FOREIGN KEY ([completed_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_ITProv_Status]        CHECK ([status] IN (
            'PENDING','IN_PROGRESS','EMAIL_CREATED','COMPLETED'))
    );
    PRINT 'Created: it_provisioning';
END
ELSE PRINT 'Skipped: it_provisioning (already exists)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. onboarding_drafts
-- ─────────────────────────────────────────────────────────────────────────────
IF OBJECT_ID('dbo.onboarding_drafts', 'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[onboarding_drafts] (
        [id]            BIGINT IDENTITY(1,1)  NOT NULL,
        [candidate_id]  BIGINT                NOT NULL,
        [draft_data]    NVARCHAR(MAX)         NOT NULL,
        [saved_by]      BIGINT                NOT NULL,
        [saved_at]      DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_onboarding_drafts]  PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [UX_Draft_Candidate]    UNIQUE ([candidate_id]),
        CONSTRAINT [FK_Draft_Candidate]    FOREIGN KEY ([candidate_id])
            REFERENCES [dbo].[candidates]([id]),
        CONSTRAINT [FK_Draft_SavedBy]      FOREIGN KEY ([saved_by])
            REFERENCES [dbo].[Users]([id])
    );
    PRINT 'Created: onboarding_drafts';
END
ELSE PRINT 'Skipped: onboarding_drafts (already exists)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. portal_events — add missing columns (additive only, never touch existing)
-- ─────────────────────────────────────────────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='portal_events' AND COLUMN_NAME='event_time')
    ALTER TABLE [dbo].[portal_events] ADD [event_time] TIME(0) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='portal_events' AND COLUMN_NAME='is_recurring_annual')
    ALTER TABLE [dbo].[portal_events] ADD [is_recurring_annual] BIT NOT NULL DEFAULT 0;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='portal_events' AND COLUMN_NAME='created_at')
    ALTER TABLE [dbo].[portal_events] ADD [created_at] DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET();

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='portal_events' AND COLUMN_NAME='updated_at')
    ALTER TABLE [dbo].[portal_events] ADD [updated_at] DATETIMEOFFSET(6) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='portal_events' AND COLUMN_NAME='is_active')
    ALTER TABLE [dbo].[portal_events] ADD [is_active] BIT NOT NULL DEFAULT 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. employee_profiles — add 8 missing columns (bank_name/iban/rib/bank_account_number already exist)
-- ─────────────────────────────────────────────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='cnss_number')
    ALTER TABLE [dbo].[employee_profiles] ADD [cnss_number] VARCHAR(100) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='cnss_affiliation_date')
    ALTER TABLE [dbo].[employee_profiles] ADD [cnss_affiliation_date] DATE NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='marital_status')
    ALTER TABLE [dbo].[employee_profiles] ADD [marital_status] VARCHAR(30) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='number_of_children')
    ALTER TABLE [dbo].[employee_profiles] ADD [number_of_children] INT NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='personal_address')
    ALTER TABLE [dbo].[employee_profiles] ADD [personal_address] NVARCHAR(500) NULL;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='candidate_id')
BEGIN
    ALTER TABLE [dbo].[employee_profiles] ADD [candidate_id] BIGINT NULL;
    -- FK only if column was just added
    ALTER TABLE [dbo].[employee_profiles]
        ADD CONSTRAINT [FK_EmpProfile_Candidate]
        FOREIGN KEY ([candidate_id]) REFERENCES [dbo].[candidates]([id]);
END;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='onboarding_completed')
    ALTER TABLE [dbo].[employee_profiles] ADD [onboarding_completed] BIT NOT NULL DEFAULT 0;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='onboarding_completed_at')
    ALTER TABLE [dbo].[employee_profiles] ADD [onboarding_completed_at] DATETIMEOFFSET(6) NULL;

-- NOTE: Roles and RolePermissions tables are managed exclusively by the Timesheet app.
-- Do NOT add any DDL touching [dbo].[Roles] or [dbo].[RolePermissions] here.
