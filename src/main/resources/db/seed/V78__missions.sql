-- ============================================================
-- V78 — Missions (ordres de mission) + billeterie
-- Created: 2026-08-21
--
-- The mission process, end to end, owned by rh-service:
--   1. a manager plans a mission for someone in their team   → PENDING_HR
--   2. RH prices it in the "billeterie" screen and validates → PENDING_FINANCE
--   3. finance takes the final decision                      → APPROVED / REJECTED_FINANCE
--   4. the employee sees it in the shell calendar and may ask
--      for a period change or a cancellation                 → mission_change_requests
--
-- Finance reads these rows through the RH API (environment.hrApiUrl), exactly as it
-- already does for candidate_cost_approvals — no table is duplicated in DAF360_FACT.
--
-- NOTE (deploy): rh-service has NO Flyway runner. Apply this script manually against
-- DAF360_HR BEFORE deploying the code, or every mission call will 500.
-- ============================================================

USE [DAF360_HR];
GO

-- ── Table 1: missions ─────────────────────────────────────────────────────────
IF OBJECT_ID(N'[dbo].[missions]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[missions] (
        [id]                    BIGINT IDENTITY(1,1) NOT NULL,

        -- Tenant of the mission = the pays of the employee sent on it. Copied at
        -- creation so the finance/RH queues can filter without joining Users.
        [pays_id]               BIGINT               NULL,

        -- Users.id of the traveller. NOT an employee_profile_id: the whole workflow
        -- is driven off the role hierarchy (Users.role_id), and the self-service /
        -- calendar screens only ever know the caller's user id.
        [employee_user_id]      BIGINT               NOT NULL,
        -- The manager who planned it.
        [created_by]            BIGINT               NOT NULL,

        -- "Responsable" of the mission. A user id when it is a colleague; the free-text
        -- column covers an external contact (client side, partner…). The service
        -- requires at least one of the two.
        [responsable_user_id]   BIGINT               NULL,
        [responsable_name]      NVARCHAR(255)        NULL,

        -- Short subject line. The calendar and the self-service card need something to
        -- print; `details` is the long form.
        [title]                 NVARCHAR(255)        NOT NULL,
        [details]               NVARCHAR(MAX)        NULL,

        -- Whole days, in the mission's own country. Deliberately DATE and not
        -- DATETIMEOFFSET: the hours that matter are the travel times, which live on
        -- mission_expenses.outbound_at / return_at.
        [start_date]            DATE                 NOT NULL,
        [end_date]              DATE                 NOT NULL,

        [scope]                 NVARCHAR(20)         NOT NULL,
        -- Destination. `destination_pays_id` when the country is one of ours,
        -- `country_label` otherwise (an international mission to a country where the
        -- group has no entity is the normal case).
        [destination_pays_id]   BIGINT               NULL,
        [country_label]         NVARCHAR(120)        NULL,
        [city]                  NVARCHAR(120)        NOT NULL,
        [address]               NVARCHAR(500)        NULL,

        -- RESERVED, deliberately unused for now: the day a mission has to be charged to
        -- a project, this is the link finance will allocate on. Nothing reads it yet.
        [affaire_id]            BIGINT               NULL,

        [status]                NVARCHAR(30)         NOT NULL DEFAULT 'PENDING_HR',

        [hr_validated_by]       BIGINT               NULL,
        [hr_validated_at]       DATETIMEOFFSET(6)    NULL,
        [hr_notes]              NVARCHAR(1000)       NULL,

        [finance_decided_by]    BIGINT               NULL,
        [finance_decided_at]    DATETIMEOFFSET(6)    NULL,
        [finance_notes]         NVARCHAR(1000)       NULL,

        [cancelled_by]          BIGINT               NULL,
        [cancelled_at]          DATETIMEOFFSET(6)    NULL,
        [cancellation_reason]   NVARCHAR(500)        NULL,

        [created_at]            DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]            DATETIMEOFFSET(6)    NULL,

        CONSTRAINT [PK_missions] PRIMARY KEY ([id]),
        CONSTRAINT [CK_Mission_Status] CHECK ([status] IN (
            'PENDING_HR', 'REJECTED_HR', 'PENDING_FINANCE',
            'APPROVED', 'REJECTED_FINANCE', 'CANCELLED'
        )),
        CONSTRAINT [CK_Mission_Scope] CHECK ([scope] IN ('NATIONAL', 'INTERNATIONAL')),
        CONSTRAINT [CK_Mission_Dates] CHECK ([end_date] >= [start_date])
    );

    -- The employee's own missions (self-service + calendar range query).
    CREATE NONCLUSTERED INDEX [IX_missions_employee]
        ON [dbo].[missions]([employee_user_id], [start_date]);
    -- The two approval queues.
    CREATE NONCLUSTERED INDEX [IX_missions_status_pays]
        ON [dbo].[missions]([status], [pays_id]);
    -- A manager's own list.
    CREATE NONCLUSTERED INDEX [IX_missions_created_by]
        ON [dbo].[missions]([created_by]);
END
GO

-- ── Table 2: mission_expenses ─────────────────────────────────────────────────
-- One row per mission, created empty when RH opens the billeterie form.
IF OBJECT_ID(N'[dbo].[mission_expenses]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[mission_expenses] (
        [id]                    BIGINT IDENTITY(1,1) NOT NULL,
        [mission_id]            BIGINT               NOT NULL,

        [currency]              NVARCHAR(3)          NOT NULL DEFAULT 'TND',

        -- Frais de mission (per diem). The daily rate is informative; `mission_allowance`
        -- is the amount that counts and enters the total.
        [allowance_daily_rate]  DECIMAL(18,4)        NULL,
        [mission_allowance]     DECIMAL(18,4)        NULL,

        -- Coût de logement.
        [lodging_cost]          DECIMAL(18,4)        NULL,
        [hotel_name]            NVARCHAR(255)        NULL,
        [nights]                INT                  NULL,
        -- Numéro de réservation (hôtel / agence).
        [reservation_number]    NVARCHAR(100)        NULL,

        -- Billet de transport.
        [transport_mode]        NVARCHAR(20)         NULL,
        [transport_carrier]     NVARCHAR(120)        NULL,
        [ticket_reference]      NVARCHAR(100)        NULL,
        [ticket_cost]           DECIMAL(18,4)        NULL,
        -- The only hours in the whole process, hence DATETIMEOFFSET and not DATE.
        [outbound_at]           DATETIMEOFFSET(6)    NULL,
        [return_at]             DATETIMEOFFSET(6)    NULL,
        -- RESERVED: scanned ticket / réservation. No upload UI yet.
        [ticket_document_url]   NVARCHAR(500)        NULL,

        [visa_fees]             DECIMAL(18,4)        NULL,
        [insurance_fees]        DECIMAL(18,4)        NULL,
        [other_fees]            DECIMAL(18,4)        NULL,
        [other_fees_label]      NVARCHAR(255)        NULL,

        -- Avance remise à l'employé, and how.
        [advance_amount]        DECIMAL(18,4)        NULL,
        [payment_method]        NVARCHAR(20)         NULL,

        -- "Date de récupération" — two dates, because the cash and the papers are
        -- almost never picked up on the same day.
        [cash_pickup_date]      DATE                 NULL,
        [document_pickup_date]  DATE                 NULL,

        -- Sum of every amount above. Always recomputed server-side; a client-sent
        -- value is ignored.
        [total_estimated_cost]  DECIMAL(18,4)        NOT NULL DEFAULT 0,

        [hr_notes]              NVARCHAR(2000)       NULL,
        [prepared_by]           BIGINT               NULL,
        [prepared_at]           DATETIMEOFFSET(6)    NULL,
        [updated_at]            DATETIMEOFFSET(6)    NULL,

        CONSTRAINT [PK_mission_expenses] PRIMARY KEY ([id]),
        CONSTRAINT [FK_MissionExpenses_Mission]
            FOREIGN KEY ([mission_id]) REFERENCES [dbo].[missions]([id]),
        CONSTRAINT [UQ_MissionExpenses_Mission] UNIQUE ([mission_id]),
        CONSTRAINT [CK_MissionExpenses_Transport] CHECK ([transport_mode] IS NULL OR [transport_mode] IN (
            'AVION', 'TRAIN', 'BUS', 'VOITURE', 'BATEAU', 'AUTRE'
        )),
        CONSTRAINT [CK_MissionExpenses_Payment] CHECK ([payment_method] IS NULL OR [payment_method] IN (
            'ESPECES', 'VIREMENT', 'CARTE', 'AUTRE'
        ))
    );
END
GO

-- ── Table 3: mission_change_requests ──────────────────────────────────────────
-- The employee's own asks on a validated mission: move the period, or cancel it.
-- RH resolves them; accepting a PERIOD_CHANGE moves the mission's dates, accepting a
-- CANCELLATION cancels it.
IF OBJECT_ID(N'[dbo].[mission_change_requests]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[mission_change_requests] (
        [id]                    BIGINT IDENTITY(1,1) NOT NULL,
        [mission_id]            BIGINT               NOT NULL,
        [requested_by]          BIGINT               NOT NULL,
        [request_type]          NVARCHAR(20)         NOT NULL,
        -- Only for PERIOD_CHANGE.
        [requested_start_date]  DATE                 NULL,
        [requested_end_date]    DATE                 NULL,
        [reason]                NVARCHAR(1000)       NOT NULL,
        [status]                NVARCHAR(20)         NOT NULL DEFAULT 'PENDING',
        [resolved_by]           BIGINT               NULL,
        [resolved_at]           DATETIMEOFFSET(6)    NULL,
        [resolution_notes]      NVARCHAR(1000)       NULL,
        [created_at]            DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT [PK_mission_change_requests] PRIMARY KEY ([id]),
        CONSTRAINT [FK_MissionChangeReq_Mission]
            FOREIGN KEY ([mission_id]) REFERENCES [dbo].[missions]([id]),
        CONSTRAINT [CK_MissionChangeReq_Type] CHECK ([request_type] IN ('PERIOD_CHANGE', 'CANCELLATION')),
        CONSTRAINT [CK_MissionChangeReq_Status] CHECK ([status] IN ('PENDING', 'ACCEPTED', 'REJECTED'))
    );

    CREATE NONCLUSTERED INDEX [IX_mission_change_req_mission]
        ON [dbo].[mission_change_requests]([mission_id]);
    CREATE NONCLUSTERED INDEX [IX_mission_change_req_status]
        ON [dbo].[mission_change_requests]([status]);
END
GO

-- ── Table 4: mission_status_history ───────────────────────────────────────────
-- Who stamped what, and when. Same intent as offboarding_validators: the mission row
-- carries only the LAST decision of each actor, so the trail lives here.
IF OBJECT_ID(N'[dbo].[mission_status_history]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[mission_status_history] (
        [id]             BIGINT IDENTITY(1,1) NOT NULL,
        [mission_id]     BIGINT               NOT NULL,
        [from_status]    NVARCHAR(30)         NULL,
        [to_status]      NVARCHAR(30)         NOT NULL,
        [actor_user_id]  BIGINT               NULL,
        [notes]          NVARCHAR(1000)       NULL,
        [created_at]     DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT [PK_mission_status_history] PRIMARY KEY ([id]),
        CONSTRAINT [FK_MissionHistory_Mission]
            FOREIGN KEY ([mission_id]) REFERENCES [dbo].[missions]([id])
    );

    CREATE NONCLUSTERED INDEX [IX_mission_history_mission]
        ON [dbo].[mission_status_history]([mission_id], [created_at]);
END
GO
