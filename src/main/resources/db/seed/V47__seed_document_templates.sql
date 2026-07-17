-- V47 — Seed standard HR attestation templates
-- Migrates the 5 hardcoded HTML files from pdf-service/templates/arx/ into the
-- document_templates table so admins can view and edit them via "Maquettes documents".
-- DB-first: PdfDocumentService calls renderByName() and falls back to the old Node.js
-- path only when no active row is found.  This INSERT is idempotent: it skips any
-- pays+name combination that already exists.

-- ── 1. Attestation de Travail ─────────────────────────────────────────────
INSERT INTO [dbo].[document_templates]
    ([pays_id],[category],[name],[description],[html_content],[variables],[page_size],[is_active],[created_at])
SELECT
    p.id,
    N'ATTESTATION',
    N'Attestation de Travail',
    N'Attestation de travail standard (CDI/CDD)',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>{{employee.city}}, le {{document.date}}</p><p>Document {{document.ref}}</p></div><div class="doc-title">Attestation de Travail</div><p class="body-text">Je soussigne <strong>{{company.dgName}}</strong> titulaire de la CIN N&deg; {{company.dgCin}} delivree a {{company.dgCinCity}} le {{company.dgCinDate}} <strong>{{company.dgTitle}}</strong> de la societe ARX Ingenierie, atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong> titulaire de la CIN N&deg; {{employee.cin}} delivree a {{employee.cinCity}} le {{employee.cinDate}} ; occupe le poste de <strong>{{employee.position}}</strong> au sein de notre Societe et ce depuis <strong>{{employee.startDateMoisAn}}</strong> dans le cadre d''un contrat de travail a duree <strong>{{employee.contractDuration}}</strong>.</p><p class="body-text">Cette attestation est delivree a l''interesse pour servir et valoir ce que de droit.</p><div class="signature-block"><p>Le {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Code de verification : {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    N'["employee.city","employee.civilite","employee.fullName","employee.cin","employee.cinCity","employee.cinDate","employee.position","employee.startDateMoisAn","employee.contractDuration","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    N'A4', 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[document_templates] dt
    WHERE dt.pays_id = p.id AND dt.name = N'Attestation de Travail'
);
GO

-- ── 2. Attestation de Salaire ─────────────────────────────────────────────
INSERT INTO [dbo].[document_templates]
    ([pays_id],[category],[name],[description],[html_content],[variables],[page_size],[is_active],[created_at])
SELECT
    p.id,
    N'ATTESTATION',
    N'Attestation de Salaire',
    N'Attestation de salaire avec montant brut annuel et duree de contrat',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>Document {{document.ref}}</p></div><div class="doc-title">Attestation de Salaire</div><p class="body-text">Je soussigne <strong>{{company.dgName}}</strong> titulaire de la CIN N&deg; {{company.dgCin}} delivree a {{company.dgCinCity}} le {{company.dgCinDate}} <strong>{{company.dgTitle}}</strong> de la Societe ARX Ingenierie, atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong> occupe le poste de <strong>{{employee.position}}</strong> depuis le mois <strong>{{employee.startDateMoisAn}}</strong> dans la societe ARX. A ce titre, {{employee.civilite}} {{employee.fullName}} beneficie d''un contrat a duree <strong>{{employee.contractDuration}}</strong> et touche un salaire brut annuel de <strong>{{employee.salaireBrutAnnuel}} TND ({{employee.salaireBrutAnnuelEnLettres}} millimes)</strong>.</p><p class="body-text">Cette attestation est delivree pour servir et valoir ce que de droit.</p><div class="signature-block"><p>Le {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Code de verification : {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    N'["employee.civilite","employee.fullName","employee.position","employee.startDateMoisAn","employee.contractDuration","employee.salaireBrutAnnuel","employee.salaireBrutAnnuelEnLettres","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    N'A4', 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[document_templates] dt
    WHERE dt.pays_id = p.id AND dt.name = N'Attestation de Salaire'
);
GO

-- ── 3. Attestation de Non-Benefice de Pret ────────────────────────────────
INSERT INTO [dbo].[document_templates]
    ([pays_id],[category],[name],[description],[html_content],[variables],[page_size],[is_active],[created_at])
SELECT
    p.id,
    N'ATTESTATION',
    N'Attestation de Non-Benefice de Pret',
    N'Attestation attestant l''absence de pret en cours aupres de la societe',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>Document {{document.ref}}</p></div><div class="doc-title">Attestation de Non-Benefice de Pret en Cours</div><p class="body-text">Je soussigne <strong>{{company.dgName}}</strong> titulaire de la CIN N&deg; {{company.dgCin}} delivree a {{company.dgCinCity}} le {{company.dgCinDate}} <strong>{{company.dgTitle}}</strong> de la Societe ARX Ingenierie, atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong> titulaire de la CIN N&deg; {{employee.cin}} delivree a {{employee.cinCity}} le {{employee.cinDate}} n''a pas beneficie(e) d''aucun emprunt en cours aupres de la societe ARX Ingenierie.</p><p class="body-text">Cette attestation est delivree a l''interesse pour servir et valoir ce que de droit.</p><div class="signature-block"><p>Le {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Code de verification : {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    N'["employee.civilite","employee.fullName","employee.cin","employee.cinCity","employee.cinDate","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    N'A4', 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[document_templates] dt
    WHERE dt.pays_id = p.id AND dt.name = N'Attestation de Non-Benefice de Pret'
);
GO

-- ── 4. Attestation de Titularisation ─────────────────────────────────────
INSERT INTO [dbo].[document_templates]
    ([pays_id],[category],[name],[description],[html_content],[variables],[page_size],[is_active],[created_at])
SELECT
    p.id,
    N'ATTESTATION',
    N'Attestation de Titularisation',
    N'Attestation de titularisation en contrat a duree indeterminee',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>{{employee.city}}, le {{document.date}}</p><p>Document {{document.ref}}</p></div><div class="doc-title">Attestation de Titularisation</div><p class="body-text">Je soussigne <strong>{{company.dgName}}</strong> titulaire de la CIN N&deg; {{company.dgCin}} delivree a {{company.dgCinCity}} le {{company.dgCinDate}} <strong>{{company.dgTitle}}</strong> de la Societe ARX Ingenierie, atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong> titulaire de la CIN N&deg; {{employee.cin}} delivree a {{employee.cinCity}} le {{employee.cinDate}} a ete titularise(e) au poste de <strong>{{employee.position}}</strong> au sein de notre societe a compter du <strong>{{employee.titularisationDate}}</strong>.</p><p class="body-text">{{employee.civilite}} {{employee.fullName}} beneficie a ce titre d''un contrat de travail a duree indeterminee conformement a la legislation sociale en vigueur.</p><p class="body-text">Cette attestation est delivree a l''interesse pour servir et valoir ce que de droit.</p><div class="signature-block"><p>Le {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Code de verification : {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    N'["employee.city","employee.civilite","employee.fullName","employee.cin","employee.cinCity","employee.cinDate","employee.position","employee.titularisationDate","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    N'A4', 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[document_templates] dt
    WHERE dt.pays_id = p.id AND dt.name = N'Attestation de Titularisation'
);
GO

-- ── 5. Attestation de Domiciliation de Salaire ───────────────────────────
INSERT INTO [dbo].[document_templates]
    ([pays_id],[category],[name],[description],[html_content],[variables],[page_size],[is_active],[created_at])
SELECT
    p.id,
    N'ATTESTATION',
    N'Attestation de Domiciliation de Salaire',
    N'Attestation de domiciliation bancaire du salaire',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .bank-block { margin: 4mm 0 4mm 30mm; line-height: 2; } .bank-row { display: flex; gap: 8mm; } .bank-label { font-weight: bold; min-width: 20mm; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>{{employee.city}}, le {{document.date}}</p><p>Document {{document.ref}}</p></div><div class="doc-title">Attestation de Domiciliation de Salaire</div><p class="body-text">Je soussigne <strong>{{company.dgName}}</strong> titulaire de la CIN N&deg; {{company.dgCin}} delivree a {{company.dgCinCity}} le {{company.dgCinDate}} <strong>{{company.dgTitle}}</strong> de la Societe ARX Ingenierie, atteste par la presente que le salaire de <strong>{{employee.civilite}} {{employee.fullName}}</strong>, occupant le poste de <strong>{{employee.position}}</strong> au sein de notre societe depuis le <strong>{{employee.startDate}}</strong>, est domicilie a la banque suivante :</p><div class="bank-block"><div class="bank-row"><span class="bank-label">Banque :</span><span>{{employee.bank}}</span></div><div class="bank-row"><span class="bank-label">RIB :</span><span>{{employee.rib}}</span></div><div class="bank-row"><span class="bank-label">IBAN :</span><span>{{employee.iban}}</span></div></div><p class="body-text">Le virement du salaire est effectue mensuellement sur ce compte.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse pour servir et valoir ce que de droit.</p><div class="signature-block"><p>Le {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Code de verification : {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    N'["employee.city","employee.civilite","employee.fullName","employee.position","employee.startDate","employee.bank","employee.rib","employee.iban","company.dgName","company.dgTitle","company.dgCin","company.dgCinCity","company.dgCinDate","document.date","document.ref","document.verificationCode"]',
    N'A4', 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[document_templates] dt
    WHERE dt.pays_id = p.id AND dt.name = N'Attestation de Domiciliation de Salaire'
);
GO
