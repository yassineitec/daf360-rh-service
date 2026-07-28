-- V48 — Update document template HTML to match reviewed PDF designs
-- Changes:
--   1. Attestation de Non-Benefice de Pret: signature uses static Direction / Cachet et Signature
--      instead of DG variables (matches V2 PDF, company stamps, no personalized signer name).
--   2. Attestation de Salaire: adds Salaire Annuel Net line after the Brut line.
--   3. Lettre d'Invitation ARX France: sender block moved inside header (right side);
--      date now uses sender.city (Courbevoie) not employee.city (Tunis).

-- ── 1. Non-Benefice de Pret: static signature ─────────────────────────────────
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        [html_content],
        N'<p>{{company.dgTitle}}</p><br><br><p><em>{{company.dgName}}</em></p>',
        N'<p>Direction</p><br><br><p><em>Cachet et Signature</em></p>'
    ),
    [variables]  = N'["employee.city","employee.civilite","employee.fullName","employee.cin","employee.cinCity","employee.cinDate","document.date","document.ref","document.verificationCode"]',
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Attestation de Non-Benefice de Pret';
GO

-- ── 2. Attestation de Salaire: add net annual salary line ─────────────────────
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        [html_content],
        N'soit <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>.</p>',
        N'soit <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>. Ceci est l''equivalent d''un Salaire Annuel Net De <strong>{{employee.salaireNetAnnuel}} Dinars Tunisiens (TND)</strong>.</p>'
    ),
    [variables]  = N'["employee.city","employee.civilite","employee.fullName","employee.cin","employee.cinCity","employee.cinDate","employee.startDateMoisAn","employee.salaireBrutAnnuel","employee.salaireBrutAnnuelEnLettres","employee.salaireNetAnnuel","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Attestation de Salaire';
GO

-- ── 3. Lettre d'Invitation: sender in header right, date from sender.city ─────
-- Three nested REPLACEs applied inside-out:
--   a) CSS: remove margin-bottom from .sender-block, add text-align: right
--   b) Structure: remove one </div> before sender-block (was closing header prematurely)
--   c) Structure + date: add </div> after sender-block to close header,
--      change date city from {{employee.city}} to {{sender.city}}
UPDATE [dbo].[document_templates]
SET
    [html_content] = REPLACE(
        REPLACE(
            REPLACE(
                [html_content],
                N'.sender-block { margin-bottom: 8mm; font-size: 10.5pt; line-height: 1.6; }',
                N'.sender-block { font-size: 10.5pt; line-height: 1.6; text-align: right; }'
            ),
            N'</div></div></div><div class="sender-block">',
            N'</div></div><div class="sender-block">'
        ),
        N'{{sender.city}}</div><div class="date-recipient-row"><div class="doc-date">{{employee.city}}, le {{document.date}}',
        N'{{sender.city}}</div></div><div class="date-recipient-row"><div class="doc-date">{{sender.city}}, le {{document.date}}'
    ),
    [updated_at] = SYSDATETIMEOFFSET()
WHERE [name] = N'Lettre d''Invitation ARX France';
GO
