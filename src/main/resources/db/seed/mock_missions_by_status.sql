-- ============================================================================
--  MOCK DATA — Missions in every status + billeterie state
-- ----------------------------------------------------------------------------
--  Populates 9 missions covering the whole process, so every screen has
--  something to show for user 207:
--
--   #  status            expenses  who is 207 here   the screen it feeds
--   ── ────────────────── ───────── ───────────────── ─────────────────────────
--   1  PENDING_HR         none      manager           billeterie « Frais à renseigner »
--   2  PENDING_HR         filled    manager           billeterie, ready to validate
--   3  PENDING_FINANCE    filled    EMPLOYEE          finance queue + his self-service
--                                                     (+ a pending CANCELLATION ask)
--   4  APPROVED           filled    EMPLOYEE          his calendar (24–28 Aug) + self-service
--   5  APPROVED           filled    manager           billeterie « demandes » (PERIOD_CHANGE)
--   6  REJECTED_HR        none      manager           his closed tab, with the HR reason
--   7  REJECTED_FINANCE   filled    manager           his closed tab, with the finance reason
--   8  CANCELLED          none      manager           his closed tab
--   9  APPROVED           filled    EMPLOYEE          calendar ACROSS a month boundary
--                                                     (30 Aug → 3 Sep) — exercises the
--                                                     per-day expansion + clipping
--
--  Self-resolving: the only hard-coded id is @me (207). The colleagues are
--  picked at random from active Users, preferring 207's own pays so the RH and
--  finance queues (which filter on pays_id) actually show them.
--
--  Idempotent: the script deletes its own previous rows first, matched on the 9
--  exact titles below, so re-running replaces them instead of piling up.
--
--  It used to tag `details` with a '[SEED:MISSIONS-MOCK]' marker and delete on
--  that — but `details` is RENDERED (the RH drawer, the finance detail modal and
--  the employee's self-service card all print it), so the marker showed up in the
--  UI. The titles are the anchor now; nothing about the seed is visible any more.
--
--  Requires V78__missions.sql to have been applied.
--  NOT a Flyway/ordered migration — run it manually against DAF360_HR.
-- ============================================================================
USE [DAF360_HR];
GO

SET NOCOUNT ON;
SET XACT_ABORT ON;
BEGIN TRAN;

DECLARE @me BIGINT = 207;          -- <<< change this if you test as someone else

-- The 9 titles this script owns. They are the idempotency key: on re-run these rows
-- are deleted and rebuilt, and nothing else is ever touched.
DECLARE @titles TABLE (title NVARCHAR(255) PRIMARY KEY);
INSERT INTO @titles VALUES
    (N'Audit de conformité — site de Sfax'),
    (N'Déploiement du nouveau parc informatique'),
    (N'Atelier de cadrage du lot 2 — client Atos'),
    (N'Recette du module facturation — agence Sousse'),
    (N'Négociation du renouvellement — filiale Égypte'),
    (N'Salon professionnel — Tunis'),
    (N'Conférence secteur et rendez-vous partenaire'),
    (N'Intervention corrective — client Gabès'),
    (N'Inventaire annuel — dépôt de Bizerte');

-- ── Guard: the user must exist, or every FK-less id below would be dangling ──
IF NOT EXISTS (SELECT 1 FROM [dbo].[Users] WHERE id = @me)
BEGIN
    RAISERROR(N'User %I64d not found in DAF360_HR.Users — set @me to your own id.', 16, 1, @me);
    ROLLBACK TRAN;
    RETURN;
END

DECLARE @myPays BIGINT = (SELECT pays_id FROM [dbo].[Users] WHERE id = @me);

-- ── Wipe this script's previous run (and only that) ──────────────────────────
-- Children first: mission_change_requests and mission_status_history both FK to
-- missions, so deleting the parent first would fail on the constraint.
DECLARE @old TABLE (id BIGINT PRIMARY KEY);
INSERT INTO @old (id)
SELECT m.id FROM [dbo].[missions] m JOIN @titles t ON t.title = m.title;

DELETE FROM [dbo].[mission_change_requests] WHERE mission_id IN (SELECT id FROM @old);
DELETE FROM [dbo].[mission_status_history]  WHERE mission_id IN (SELECT id FROM @old);
DELETE FROM [dbo].[mission_expenses]        WHERE mission_id IN (SELECT id FROM @old);
DELETE FROM [dbo].[missions]                WHERE id         IN (SELECT id FROM @old);

-- ── Pick 5 random colleagues ────────────────────────────────────────────────
-- Same pays as @me when possible: `missions.pays_id` is copied from the employee
-- and the RH/finance queues filter on it, so a colleague from another country
-- would create missions that never appear in the queue you are testing.
-- NEWID() = a different cast on every run, which is the point of mock data.
DECLARE @team TABLE (rn INT PRIMARY KEY, user_id BIGINT, pays_id BIGINT);

INSERT INTO @team (rn, user_id, pays_id)
SELECT TOP 5 ROW_NUMBER() OVER (ORDER BY NEWID()), u.id, u.pays_id
FROM [dbo].[Users] u
WHERE u.isActive = 1
  AND u.id <> @me
  AND (@myPays IS NULL OR u.pays_id = @myPays);

-- Fallback: on a thin dev database there may be nobody in the same pays.
IF (SELECT COUNT(*) FROM @team) < 5
BEGIN
    DELETE FROM @team;
    INSERT INTO @team (rn, user_id, pays_id)
    SELECT TOP 5 ROW_NUMBER() OVER (ORDER BY NEWID()), u.id, u.pays_id
    FROM [dbo].[Users] u
    WHERE u.isActive = 1 AND u.id <> @me;
END

IF (SELECT COUNT(*) FROM @team) = 0
BEGIN
    RAISERROR(N'No other active user found — cannot build a team for user %I64d.', 16, 1, @me);
    ROLLBACK TRAN;
    RETURN;
END

-- COALESCE back to @me so a database with fewer than 5 other users still runs;
-- the missions just end up self-assigned rather than failing.
DECLARE @c1 BIGINT = COALESCE((SELECT user_id FROM @team WHERE rn = 1), @me);
DECLARE @c2 BIGINT = COALESCE((SELECT user_id FROM @team WHERE rn = 2), @c1);
DECLARE @c3 BIGINT = COALESCE((SELECT user_id FROM @team WHERE rn = 3), @c1);
DECLARE @c4 BIGINT = COALESCE((SELECT user_id FROM @team WHERE rn = 4), @c1);
DECLARE @c5 BIGINT = COALESCE((SELECT user_id FROM @team WHERE rn = 5), @c1);

DECLARE @p1 BIGINT = COALESCE((SELECT pays_id FROM @team WHERE rn = 1), @myPays);
DECLARE @p2 BIGINT = COALESCE((SELECT pays_id FROM @team WHERE rn = 2), @myPays);
DECLARE @p4 BIGINT = COALESCE((SELECT pays_id FROM @team WHERE rn = 4), @myPays);
DECLARE @p5 BIGINT = COALESCE((SELECT pays_id FROM @team WHERE rn = 5), @myPays);

DECLARE @now DATETIMEOFFSET(6) = SYSDATETIMEOFFSET();
DECLARE @m BIGINT;   -- last inserted mission id, reused after each INSERT

-- ============================================================================
--  1. PENDING_HR, no expense sheet — the billeterie row that cannot be validated
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, created_at)
VALUES
    (@p1, @c1, @me, @me, NULL,
     N'Audit de conformité — site de Sfax',
     N'Vérification des procédures qualité sur site, entretiens avec les chefs d''équipe.',
     '2026-09-07', '2026-09-09', 'NATIONAL', NULL, N'Sfax', N'Zone industrielle Poudrière 2',
     'PENDING_HR', DATEADD(DAY, -2, @now));
SET @m = SCOPE_IDENTITY();
INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL, 'PENDING_HR', @me, N'Mission planifiée', DATEADD(DAY, -2, @now));

-- ============================================================================
--  2. PENDING_HR, expenses filled — ready for the RH « Valider »
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, created_at)
VALUES
    (@p2, @c2, @me, @c1, NULL,
     N'Déploiement du nouveau parc informatique',
     N'Installation et recette des postes, formation courte des utilisateurs.',
     '2026-09-01', '2026-09-05', 'NATIONAL', NULL, N'Sousse', N'Agence Sousse Riadh',
     'PENDING_HR', DATEADD(DAY, -4, @now));
SET @m = SCOPE_IDENTITY();

-- total = 600 (frais) + 750 (logement) + 80 (autres) = 1430
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_reference, ticket_cost,
     other_fees, other_fees_label, advance_amount, payment_method,
     cash_pickup_date, document_pickup_date, total_estimated_cost,
     hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'TND', 120.0000, 600.0000,
     750.0000, N'Ibis Sousse', 4, N'HTL-99231',
     'VOITURE', N'Flotte ITEC', NULL, NULL,
     80.0000, N'Péage et carburant', 400.0000, 'ESPECES',
     '2026-08-28', '2026-08-29', 1430.0000,
     N'Véhicule de service réservé, pas de billet à émettre.', @me, DATEADD(DAY, -1, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL, 'PENDING_HR', @me, N'Mission planifiée', DATEADD(DAY, -4, @now));

-- ============================================================================
--  3. PENDING_FINANCE — 207 is the traveller. Finance queue + his self-service,
--     and it carries a PENDING cancellation ask (billeterie « demandes »).
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes, created_at)
VALUES
    (@myPays, @me, @c3, NULL, N'Marc Lefèvre (client Atos)',
     N'Atelier de cadrage du lot 2 — client Atos',
     N'Atelier de cadrage du lot 2 chez le client, restitution le dernier jour.',
     '2026-09-14', '2026-09-18', 'INTERNATIONAL', N'France', N'Paris', N'La Défense, tour Manhattan',
     'PENDING_FINANCE', @c4, DATEADD(DAY, -1, @now),
     N'Billet émis, hôtel confirmé. Avance à retirer avant le départ.', DATEADD(DAY, -6, @now));
SET @m = SCOPE_IDENTITY();

-- total = 450 + 620 + 480 + 60 + 45 + 35 = 1690
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_reference, ticket_cost,
     outbound_at, return_at,
     visa_fees, insurance_fees, other_fees, other_fees_label,
     advance_amount, payment_method, cash_pickup_date, document_pickup_date,
     total_estimated_cost, hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'EUR', 90.0000, 450.0000,
     620.0000, N'Ibis Paris La Défense', 4, N'BKG-771204',
     'AVION', N'Tunisair', N'TU-7741-PAR', 480.0000,
     '2026-09-14T06:40:00+01:00', '2026-09-18T20:15:00+01:00',
     60.0000, 45.0000, 35.0000, N'Transferts aéroport',
     300.0000, 'VIREMENT', '2026-09-10', '2026-09-11',
     1690.0000, N'Visa Schengen déjà déposé, retour du passeport le 11/09.', @c4, DATEADD(DAY, -1, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,              'PENDING_HR',      @c3, N'Mission planifiée',                              DATEADD(DAY, -6, @now)),
       (@m, 'PENDING_HR',      'PENDING_FINANCE', @c4, N'Validation RH — coût estimé 1690.0000 EUR',      DATEADD(DAY, -1, @now));

-- The employee's own ask. Allowed on PENDING_FINANCE as well as APPROVED, which
-- is exactly what MissionService#requestChange accepts.
INSERT INTO [dbo].[mission_change_requests]
    (mission_id, requested_by, request_type, requested_start_date, requested_end_date,
     reason, status, created_at)
VALUES
    (@m, @me, 'CANCELLATION', NULL, NULL,
     N'L''atelier client est reporté sine die, la mission n''a plus d''objet.',
     'PENDING', DATEADD(HOUR, -5, @now));

-- ============================================================================
--  4. APPROVED — 207 is the traveller, inside the CURRENT month, so it lands on
--     the home calendar straight away (24 → 28 August).
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes,
     finance_decided_by, finance_decided_at, finance_notes, created_at)
VALUES
    (@myPays, @me, @c3, @c3, NULL,
     N'Recette du module facturation — agence Sousse',
     N'Recette fonctionnelle avec les key users, PV de recette à faire signer.',
     '2026-08-24', '2026-08-28', 'NATIONAL', NULL, N'Sousse', N'Agence Sousse Riadh, 2e étage',
     'APPROVED', @c4, DATEADD(DAY, -9, @now), N'Train réservé, hôtel confirmé.',
     @c5, DATEADD(DAY, -8, @now), N'Coût conforme au barème national.', DATEADD(DAY, -12, @now));
SET @m = SCOPE_IDENTITY();

-- total = 750 + 900 + 90 + 40 = 1780
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_reference, ticket_cost,
     outbound_at, return_at, insurance_fees,
     advance_amount, payment_method, cash_pickup_date, document_pickup_date,
     total_estimated_cost, hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'TND', 150.0000, 750.0000,
     900.0000, N'Golden Tulip Sousse', 4, N'HTL-40112',
     'TRAIN', N'SNCFT', N'SNCFT-88213', 90.0000,
     '2026-08-24T07:10:00+01:00', '2026-08-28T18:35:00+01:00', 40.0000,
     500.0000, 'ESPECES', '2026-08-22', '2026-08-22',
     1780.0000, N'Avance à retirer à la caisse, billets remis avec.', @c4, DATEADD(DAY, -9, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,              'PENDING_HR',      @c3, N'Mission planifiée',                          DATEADD(DAY, -12, @now)),
       (@m, 'PENDING_HR',      'PENDING_FINANCE', @c4, N'Validation RH — coût estimé 1780.0000 TND',  DATEADD(DAY,  -9, @now)),
       (@m, 'PENDING_FINANCE', 'APPROVED',        @c5, N'Coût conforme au barème national.',          DATEADD(DAY,  -8, @now));

-- ============================================================================
--  5. APPROVED with a PENDING period-change ask — the billeterie « demandes » tab
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes,
     finance_decided_by, finance_decided_at, finance_notes, created_at)
VALUES
    (@p1, @c1, @me, @me, NULL,
     N'Négociation du renouvellement — filiale Égypte',
     N'Revue de contrat avec la direction locale, préparation de l''avenant.',
     '2026-09-21', '2026-09-25', 'INTERNATIONAL', N'Égypte', N'Le Caire', N'New Cairo, Business Plaza',
     'APPROVED', @c4, DATEADD(DAY, -5, @now), N'Vol et hôtel confirmés, visa obtenu.',
     @c5, DATEADD(DAY, -4, @now), N'Accepté, à imputer sur le budget développement.', DATEADD(DAY, -10, @now));
SET @m = SCOPE_IDENTITY();

-- total = 6000 + 4800 + 5200 + 900 + 600 + 400 = 17900
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_reference, ticket_cost,
     outbound_at, return_at,
     visa_fees, insurance_fees, other_fees, other_fees_label,
     advance_amount, payment_method, cash_pickup_date, document_pickup_date,
     total_estimated_cost, hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'EGP', 1200.0000, 6000.0000,
     4800.0000, N'Steigenberger Cairo', 4, N'BKG-CAI-3391',
     'AVION', N'EgyptAir', N'MS-2210-CAI', 5200.0000,
     '2026-09-21T09:25:00+01:00', '2026-09-25T22:40:00+01:00',
     900.0000, 600.0000, 400.0000, N'Taxis et pourboires',
     3000.0000, 'VIREMENT', '2026-09-16', '2026-09-17',
     17900.0000, N'Passeport à récupérer le 17/09 au service RH.', @c4, DATEADD(DAY, -5, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,              'PENDING_HR',      @me, N'Mission planifiée',                            DATEADD(DAY, -10, @now)),
       (@m, 'PENDING_HR',      'PENDING_FINANCE', @c4, N'Validation RH — coût estimé 17900.0000 EGP',   DATEADD(DAY,  -5, @now)),
       (@m, 'PENDING_FINANCE', 'APPROVED',        @c5, N'Accepté, à imputer sur le budget développement.', DATEADD(DAY, -4, @now));

-- Accepting this one moves the mission to 28/09 → 02/10; refusing it needs a reason.
INSERT INTO [dbo].[mission_change_requests]
    (mission_id, requested_by, request_type, requested_start_date, requested_end_date,
     reason, status, created_at)
VALUES
    (@m, @c1, 'PERIOD_CHANGE', '2026-09-28', '2026-10-02',
     N'La direction locale n''est disponible qu''à partir du 28 septembre.',
     'PENDING', DATEADD(HOUR, -20, @now));

-- ============================================================================
--  6. REJECTED_HR — terminal, with the reason RH gave
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes, created_at)
VALUES
    (@p2, @c2, @me, @me, NULL,
     N'Salon professionnel — Tunis',
     N'Veille concurrentielle et prise de contacts sur le salon.',
     '2026-08-10', '2026-08-12', 'NATIONAL', NULL, N'Tunis', N'Parc des expositions du Kram',
     'REJECTED_HR', @c4, DATEADD(DAY, -14, @now),
     N'Budget déplacements de la période déjà consommé, à replanifier au T4.', DATEADD(DAY, -16, @now));
SET @m = SCOPE_IDENTITY();
INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,         'PENDING_HR',  @me, N'Mission planifiée',                                                 DATEADD(DAY, -16, @now)),
       (@m, 'PENDING_HR', 'REJECTED_HR', @c4, N'Budget déplacements de la période déjà consommé, à replanifier au T4.', DATEADD(DAY, -14, @now));

-- ============================================================================
--  7. REJECTED_FINANCE — RH had priced it, finance said no
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes,
     finance_decided_by, finance_decided_at, finance_notes, created_at)
VALUES
    (@p4, @c4, @me, NULL, N'Sarah Bennani (partenaire Gulf Tech)',
     N'Conférence secteur et rendez-vous partenaire',
     N'Conférence GITEX et deux rendez-vous partenaires en marge du salon.',
     '2026-10-05', '2026-10-10', 'INTERNATIONAL', N'Émirats arabes unis', N'Dubaï',
     N'Dubai World Trade Centre',
     'REJECTED_FINANCE', @c5, DATEADD(DAY, -7, @now), N'Devis agence reçu, en attente de la décision finance.',
     @c3, DATEADD(DAY, -6, @now),
     N'Coût hors barème pour une conférence : à revoir en visioconférence.', DATEADD(DAY, -11, @now));
SET @m = SCOPE_IDENTITY();

-- La fiche existe : la finance ne pouvait pas refuser un coût qu'elle n'avait pas vu.
-- total = 700 + 1900 + 890 + 120 + 60 = 3670
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_reference, ticket_cost,
     outbound_at, return_at, visa_fees, insurance_fees,
     advance_amount, payment_method, cash_pickup_date, document_pickup_date,
     total_estimated_cost, hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'EUR', 140.0000, 700.0000,
     1900.0000, N'Rove Trade Centre', 5, N'BKG-DXB-55210',
     'AVION', N'Emirates', N'EK-748-DXB', 890.0000,
     '2026-10-05T02:35:00+01:00', '2026-10-10T18:50:00+01:00', 120.0000, 60.0000,
     NULL, NULL, NULL, NULL,
     3670.0000, N'Aucune avance versée : dossier refusé avant le décaissement.', @c5, DATEADD(DAY, -7, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,              'PENDING_HR',       @me, N'Mission planifiée',                                                     DATEADD(DAY, -11, @now)),
       (@m, 'PENDING_HR',      'PENDING_FINANCE',  @c5, N'Validation RH — coût estimé 3670.0000 EUR',                            DATEADD(DAY,  -7, @now)),
       (@m, 'PENDING_FINANCE', 'REJECTED_FINANCE', @c3, N'Coût hors barème pour une conférence : à revoir en visioconférence.',  DATEADD(DAY,  -6, @now));

-- ============================================================================
--  8. CANCELLED — called off before RH answered
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, cancelled_by, cancelled_at, cancellation_reason, created_at)
VALUES
    (@p5, @c5, @me, @me, NULL,
     N'Intervention corrective — client Gabès',
     N'Reprise d''incident sur site après l''échec du correctif à distance.',
     '2026-09-28', '2026-09-30', 'NATIONAL', NULL, N'Gabès', N'Zone industrielle de Ghannouch',
     'CANCELLED', @me, DATEADD(DAY, -3, @now),
     N'Incident résolu à distance, le déplacement n''est plus nécessaire.', DATEADD(DAY, -5, @now));
SET @m = SCOPE_IDENTITY();
INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,         'PENDING_HR', @me, N'Mission planifiée',                                                DATEADD(DAY, -5, @now)),
       (@m, 'PENDING_HR', 'CANCELLED',  @me, N'Incident résolu à distance, le déplacement n''est plus nécessaire.', DATEADD(DAY, -3, @now));

-- ============================================================================
--  9. APPROVED, 207 again, ACROSS a month boundary (30 Aug → 3 Sep).
--     This is the one that proves the calendar expansion: the mission must show
--     on 30–31 August AND on 1–3 September, i.e. in both months' grids.
-- ============================================================================
INSERT INTO [dbo].[missions]
    (pays_id, employee_user_id, created_by, responsable_user_id, responsable_name,
     title, details, start_date, end_date, scope, country_label, city, address,
     status, hr_validated_by, hr_validated_at, hr_notes,
     finance_decided_by, finance_decided_at, finance_notes, created_at)
VALUES
    (@myPays, @me, @c3, @c1, NULL,
     N'Inventaire annuel — dépôt de Bizerte',
     N'Inventaire physique contradictoire avec le commissaire aux comptes.',
     '2026-08-30', '2026-09-03', 'NATIONAL', NULL, N'Bizerte', N'Dépôt Menzel Jemil',
     'APPROVED', @c4, DATEADD(DAY, -6, @now), N'Hébergement sur place, véhicule de service.',
     @c5, DATEADD(DAY, -5, @now), N'Validé.', DATEADD(DAY, -8, @now));
SET @m = SCOPE_IDENTITY();

-- total = 600 + 700 + 60 = 1360
INSERT INTO [dbo].[mission_expenses]
    (mission_id, currency, allowance_daily_rate, mission_allowance,
     lodging_cost, hotel_name, nights, reservation_number,
     transport_mode, transport_carrier, ticket_cost,
     advance_amount, payment_method, cash_pickup_date, document_pickup_date,
     total_estimated_cost, hr_notes, prepared_by, prepared_at)
VALUES
    (@m, 'TND', 120.0000, 600.0000,
     700.0000, N'Hôtel Nador Bizerte', 4, N'HTL-51877',
     'VOITURE', N'Flotte ITEC', 60.0000,
     350.0000, 'ESPECES', '2026-08-28', '2026-08-28',
     1360.0000, N'Carburant avancé en espèces, justificatifs au retour.', @c4, DATEADD(DAY, -6, @now));

INSERT INTO [dbo].[mission_status_history] (mission_id, from_status, to_status, actor_user_id, notes, created_at)
VALUES (@m, NULL,              'PENDING_HR',      @c3, N'Mission planifiée',                         DATEADD(DAY, -8, @now)),
       (@m, 'PENDING_HR',      'PENDING_FINANCE', @c4, N'Validation RH — coût estimé 1360.0000 TND', DATEADD(DAY, -6, @now)),
       (@m, 'PENDING_FINANCE', 'APPROVED',        @c5, N'Validé.',                                  DATEADD(DAY, -5, @now));

COMMIT TRAN;
GO

-- ── What was created ────────────────────────────────────────────────────────
SELECT m.id,
       m.status,
       m.employee_user_id,
       eu.fullName          AS employee,
       m.created_by,
       cu.fullName          AS planned_by,
       m.start_date, m.end_date, m.scope, m.city,
       CASE WHEN e.mission_id IS NULL THEN N'—'
            ELSE CONCAT(CAST(e.total_estimated_cost AS NVARCHAR(30)), N' ', e.currency) END AS cost,
       (SELECT COUNT(*) FROM [dbo].[mission_change_requests] r
         WHERE r.mission_id = m.id AND r.status = 'PENDING')                                AS pending_asks
FROM [dbo].[missions] m
LEFT JOIN [dbo].[mission_expenses] e ON e.mission_id = m.id
LEFT JOIN [dbo].[Users] eu           ON eu.id = m.employee_user_id
LEFT JOIN [dbo].[Users] cu           ON cu.id = m.created_by
-- The titles again, spelled out: variables do not cross the GO above, so @titles is
-- out of scope here.
WHERE m.title IN (
    N'Audit de conformité — site de Sfax',
    N'Déploiement du nouveau parc informatique',
    N'Atelier de cadrage du lot 2 — client Atos',
    N'Recette du module facturation — agence Sousse',
    N'Négociation du renouvellement — filiale Égypte',
    N'Salon professionnel — Tunis',
    N'Conférence secteur et rendez-vous partenaire',
    N'Intervention corrective — client Gabès',
    N'Inventaire annuel — dépôt de Bizerte'
)
ORDER BY m.id;
GO
