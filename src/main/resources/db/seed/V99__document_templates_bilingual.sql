-- V99 — Dynamic FR/EN language switching for the 6 generated HR attestations.
--
-- Until now, language was a side effect of which country an employee belongs to: Tunisia's
-- document_templates rows are French, Egypt's (V49) are English, and DocumentTemplateService
-- .renderByName() picked whichever single row existed for (pays_id, name). There was no way
-- to generate the SAME document in the OTHER language for a given employee.
--
-- This migration adds a `lang` column (see also DocumentTemplate.java / V99 ALTER below) so a
-- (pays_id, name) pair can now have one row per language, selected at generation time by
-- PdfDocumentService's new `lang` parameter (defaults to "fr" for every pre-existing caller).
--
-- Scope: Tunisia (179, 130 employees) and Egypt (53, 94 employees) are the only pays with real
-- employees today, so only those two get a real translated counterpart here. Every other pays'
-- existing (dormant, 0-employee) French row is untouched and simply defaults to lang='fr'.

USE [DAF360_HR];
GO

-- ── 1. Schema: add the lang column ──────────────────────────────────────────────
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'document_templates' AND COLUMN_NAME = 'lang')
    ALTER TABLE [dbo].[document_templates]
        ADD [lang] VARCHAR(5) NOT NULL CONSTRAINT DF_doctmpl_lang DEFAULT ('fr');
GO

-- ── 2. Egypt's existing rows (seeded English by V49) are now explicitly lang='en' ──
UPDATE [dbo].[document_templates]
SET [lang] = 'en'
WHERE [pays_id] = 53
  AND [name] IN (
      N'Attestation de Travail', N'Attestation de Salaire',
      N'Attestation de Non-Benefice de Pret', N'Attestation de Titularisation',
      N'Attestation de Domiciliation de Salaire', N'Lettre d''Invitation ARX France'
  );
GO

-- ── 3. Tunisia's 6 documents, translated to English (lang='en') ─────────────────
-- Each INSERT copies pays_id/category/variables/page_size/sharepoint_location straight from
-- the existing French row by id, so only the translated description/html_content are typed
-- out here — the {{...}} token names are identical to the French row's, since
-- DocumentTemplateService.resolveContext() already localises every language-sensitive value
-- (dates, civilité, contract-duration label, amount-in-words) by the row's own `lang`.

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Employment certificate (permanent/fixed-term contract)',
    N'<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; }
.page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; }
.header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8mm; }
.arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; }
.arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; }
.company-block { text-align: right; }
.company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; }
.company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; }
.doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 22mm; }
.doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; }
.title-underline { display: block; width: 65%; border: none; border-top: 2.5px solid #000; margin: 0 auto 10mm; }
.body-text { line-height: 1.9; text-align: justify; margin: 4mm 0; font-size: 11pt; }
.signature-block { margin-top: 16mm; text-align: right; line-height: 2; font-size: 11pt; }
.footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; }
.footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <div>
      <div class="arx-logo-text">ARX</div>
      <div class="arx-tagline">S M A R T &nbsp; M I N D S</div>
    </div>
    <div class="company-block">
      <div class="company-name-hdr">ARX Tunisie</div>
      <div class="company-sub">Civil Engineering Consulting Firm</div>
    </div>
  </div>
  <div class="doc-date">{{employee.city}}, {{document.date}}</div>
  <div class="doc-title">Employment Certificate</div>
  <hr class="title-underline">
  <p class="body-text">I, the undersigned, <strong>{{company.dgName}}</strong>, holder of CIN No. <strong>{{company.dgCin}}</strong> issued in {{company.dgCinCity}} on {{company.dgCinDate}}, acting as <strong>{{company.dgTitle}}</strong> of <strong>ARX Tunisie</strong>,</p>
  <p class="body-text">hereby certify that <strong>{{employee.civilite}} {{employee.fullName}}</strong>,<br>
  holder of CIN No. <strong>{{employee.cin}}</strong> issued in Tunis on <strong>{{employee.cinDate}}</strong><br>
  holds the position of <strong>{{employee.position}}</strong> within our company, since<br>
  <strong>{{employee.startDateMoisAn}}</strong>, under a <strong>{{employee.contractDuration}}</strong> employment contract.</p>
  <p class="body-text">This certificate is issued to the interested party for all legal purposes.</p>
  <div class="signature-block">
    <p><strong>ARX Tunisie</strong></p>
    <p>{{company.dgTitle}}</p>
    <br>
    <p><em>{{company.dgName}}</em></p>
  </div>
  <div class="footer-rule"></div>
  <div class="footer">
    <div><strong>ARX Tunisie</strong> &mdash; Civil Engineering Consulting Firm</div>
    <div>Registered Office: 7 Avenue de Paris, BOUMHEL EL BASSATINE, 2097, GOVERNORATE OF BEN AROUS</div>
    <div style="margin-top:1.5mm;">Page 1 of 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref: {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Verification Code: {{document.verificationCode}}</div>
  </div>
</div>
</body>
</html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 178;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Salary certificate showing annual net salary amount (TND)',
    N'<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; }
.page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; }
.header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8mm; }
.arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; }
.arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 3px; margin-top: 2mm; }
.company-block { text-align: right; }
.company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; }
.company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; }
.doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 22mm; }
.doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; }
.title-underline { display: block; width: 60%; border: none; border-top: 2.5px solid #000; margin: 0 auto 10mm; }
.body-text { line-height: 1.9; text-align: justify; margin: 4mm 0; font-size: 11pt; }
.signature-block { margin-top: 16mm; text-align: right; line-height: 2.2; font-size: 11pt; }
.footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; }
.footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <div>
      <div class="arx-logo-text">ARX</div>
      <div class="arx-tagline">S M A R T - M I N D S</div>
    </div>
    <div class="company-block">
      <div class="company-name-hdr">ARX Tunisie</div>
      <div class="company-sub">Civil Engineering Consulting Firm</div>
    </div>
  </div>
  <div class="doc-date">{{employee.city}}, {{document.date}}</div>
  <div class="doc-title">Salary Certificate</div>
  <hr class="title-underline">
  <p class="body-text">I, the undersigned, <strong>{{company.dgName}}</strong>, holder of CIN No. <strong>{{company.dgCin}}</strong> issued in {{company.dgCinCity}} on {{company.dgCinDate}}, acting as <strong>{{company.dgTitle}}</strong> of <strong>ARX Tunisie</strong>;</p>
  <p class="body-text">hereby certify that <strong>{{employee.civilite}} {{employee.fullName}}</strong>, holder of CIN No. <strong>{{employee.cin}}</strong> issued in Tunis on {{employee.cinDate}}, and employed within our company since <strong>{{employee.startDateMoisAn}}</strong>, receives an estimated Annual Net Salary of <strong>{{employee.salaireNetAnnuel}} Tunisian Dinars (TND)</strong> i.e. <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>.</p>
  <p class="body-text">This certificate is issued to the interested party for all legal purposes.</p>
  <div class="signature-block">
    <p><strong>The Manager</strong></p>
    <br>
    <p><em>{{company.dgName}}</em></p>
  </div>
  <div class="footer-rule"></div>
  <div class="footer">
    <div><strong>ARX Tunisie</strong> &mdash; Civil Engineering Consulting Firm</div>
    <div>Registered Office: 7 Avenue de Paris, BOUMHEL EL BASSATINE, 2097, GOVERNORATE OF BEN AROUS</div>
    <div style="margin-top:1.5mm;">Page 1 of 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref: {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Verification Code: {{document.verificationCode}}</div>
  </div>
</div>
</body>
</html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 372;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Certificate declaring the employee has no outstanding loan or advance from the employer',
    N'<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; }
.page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; }
.header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8mm; }
.arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; }
.arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; }
.company-block { text-align: right; }
.company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; }
.company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; }
.doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 28mm; }
.doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; }
.title-underline { display: block; width: 72%; border: none; border-top: 2.5px solid #000; margin: 0 auto 10mm; }
.body-text { line-height: 1.9; text-align: justify; margin: 4mm 0; font-size: 11pt; }
.signature-block { margin-top: 18mm; text-align: right; line-height: 2; font-size: 11pt; }
.footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; }
.footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <div>
      <div class="arx-logo-text">ARX</div>
      <div class="arx-tagline">S M A R T &nbsp; M I N D S</div>
    </div>
    <div class="company-block">
      <div class="company-name-hdr">ARX Tunisie</div>
      <div class="company-sub">Civil Engineering Consulting Firm</div>
    </div>
  </div>
  <div class="doc-date">{{employee.city}}, {{document.date}}</div>
  <div class="doc-title">Certificate of Non-Benefit from Loan</div>
  <hr class="title-underline">
  <p class="body-text">I, the undersigned, <strong>{{employee.civilite}} {{employee.fullName}}</strong>, holder of CIN No. <strong>{{employee.cin}}</strong> issued in {{employee.cinCity}} on {{employee.cinDate}}, employed at <strong>ARX Tunisie</strong> (Civil Engineering Consulting Firm) and residing in {{employee.city}},</p>
  <p class="body-text">hereby freely and solemnly certify that I do not currently benefit from any loan, ongoing credit, or financial advance of any nature from my employer, <strong>ARX Tunisie</strong>, as of this date.</p>
  <p class="body-text">This certificate is issued to the interested party for all legal purposes.</p>
  <div class="signature-block">
    <p>The declarant</p>
  </div>
  <div class="footer-rule"></div>
  <div class="footer">
    <div><strong>ARX Tunisie</strong> &mdash; Civil Engineering Consulting Firm</div>
    <div>Registered Office: 7 Avenue de Paris, BOUMHEL EL BASSATINE, 2097, GOVERNORATE OF BEN AROUS</div>
    <div style="margin-top:1.5mm;">Page 1 of 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref: {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Verification Code: {{document.verificationCode}}</div>
  </div>
</div>
</body>
</html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 566;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Certificate confirming conversion to permanent (open-ended) employment contract',
    N'<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>{{employee.city}}, {{document.date}}</p><p>Document {{document.ref}}</p></div><div class="doc-title">Permanent Employment Certificate</div><p class="body-text">I, the undersigned <strong>{{company.dgName}}</strong>, holder of CIN No. {{company.dgCin}} issued in {{company.dgCinCity}} on {{company.dgCinDate}}, <strong>{{company.dgTitle}}</strong> of ARX Ingenierie, hereby certify that <strong>{{employee.civilite}} {{employee.fullName}}</strong>, holder of CIN No. {{employee.cin}} issued in {{employee.cinCity}} on {{employee.cinDate}}, has been confirmed on a permanent basis in the position of <strong>{{employee.position}}</strong> within our company, effective <strong>{{employee.titularisationDate}}</strong>.</p><p class="body-text">{{employee.civilite}} {{employee.fullName}} is accordingly entitled to an open-ended employment contract in accordance with applicable labour legislation.</p><p class="body-text">This certificate is issued to the interested party for all legal purposes.</p><div class="signature-block"><p>The {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Verification Code: {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 760;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Certificate confirming the bank account to which the employee salary is transferred',
    N'<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: ''Times New Roman'', Times, serif; font-size: 12pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 20mm 25mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; border-bottom: 2px solid #1a6b7c; padding-bottom: 4mm; } .doc-meta { text-align: right; font-size: 9pt; color: #666; margin-bottom: 6mm; } .doc-title { font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 1px; margin: 8mm 0 6mm; border-top: 1px solid #ddd; border-bottom: 1px solid #ddd; padding: 3mm 0; } .body-text { text-indent: 12mm; line-height: 1.9; text-align: justify; margin: 4mm 0; } .bank-block { margin: 4mm 0 4mm 30mm; line-height: 2; } .bank-row { display: flex; gap: 8mm; } .bank-label { font-weight: bold; min-width: 20mm; } .signature-block { margin-top: 16mm; text-align: right; line-height: 2; } .footer { position: absolute; bottom: 10mm; left: 25mm; right: 25mm; border-top: 1px solid #ccc; padding-top: 2mm; font-size: 8pt; color: #888; display: flex; justify-content: space-between; }</style></head><body><div class="page"><div class="header"></div><div class="doc-meta"><p>{{employee.city}}, {{document.date}}</p><p>Document {{document.ref}}</p></div><div class="doc-title">Salary Bank Account Certificate</div><p class="body-text">I, the undersigned <strong>{{company.dgName}}</strong>, holder of CIN No. {{company.dgCin}} issued in {{company.dgCinCity}} on {{company.dgCinDate}}, <strong>{{company.dgTitle}}</strong> of ARX Ingenierie, hereby certify that the salary of <strong>{{employee.civilite}} {{employee.fullName}}</strong>, holding the position of <strong>{{employee.position}}</strong> within our company since <strong>{{employee.startDate}}</strong>, is transferred to the following bank account:</p><div class="bank-block"><div class="bank-row"><span class="bank-label">Bank:</span><span>{{employee.bank}}</span></div><div class="bank-row"><span class="bank-label">Account No.:</span><span>{{employee.rib}}</span></div><div class="bank-row"><span class="bank-label">IBAN:</span><span>{{employee.iban}}</span></div></div><p class="body-text">The salary transfer is made monthly to this account.</p><p class="body-text">This certificate is issued upon the request of the interested party for all legal purposes.</p><div class="signature-block"><p>The {{company.dgTitle}}</p><p style="margin-top:12mm;"><strong>{{company.dgName}}</strong></p></div><div class="footer"><span>Verification Code: {{document.verificationCode}}</span><span>{{document.ref}} - {{document.date}}</span></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 954;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'en', name,
    N'Professional invitation letter for French visa (French Consulate General in Tunis)',
    N'<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; }
.page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; }
.header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14mm; }
.arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; }
.arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; }
.sender-block { text-align: right; font-size: 10.5pt; line-height: 1.7; }
.date-recipient-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 10mm; }
.doc-date { font-size: 10.5pt; }
.recipient-block { text-align: left; font-size: 10.5pt; line-height: 1.7; }
.subject-line { margin: 6mm 0 10mm; font-size: 10.5pt; line-height: 1.7; }
.salutation { margin-bottom: 6mm; font-size: 11pt; }
.body-text { line-height: 1.9; text-align: justify; margin: 4mm 0; font-size: 11pt; }
.signature-block { margin-top: 14mm; text-align: left; line-height: 2; font-size: 11pt; }
.footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; }
.footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <div>
      <div class="arx-logo-text">ARX</div>
      <div class="arx-tagline">S M A R T &nbsp; M I N D S</div>
    </div>
    <div class="sender-block">
      <strong>{{sender.name}}</strong><br>
      {{sender.title}}<br>
      {{sender.address}}<br>
      {{sender.city}}
    </div>
  </div>
  <div class="date-recipient-row">
    <div class="doc-date">{{sender.city}}, {{document.date}}</div>
    <div class="recipient-block">
      <strong>French Consulate General in Tunis</strong><br>
      Avenue Habib Bourguiba,<br>
      Tunis, Tunisia
    </div>
  </div>
  <div class="subject-line">
    <strong>Subject:</strong> Professional invitation for an on-site project meeting<br>
    <strong>Copy:</strong> ARX Tunisie
  </div>
  <p class="salutation">Dear Consul General,</p>
  <p class="body-text">Acting in my capacity as <strong>{{sender.title}}</strong> of <strong>Arx France</strong>, I hereby inform you of my intention to invite the following foreign national to France: <strong>{{employee.civilite}} {{employee.fullName}}</strong>, born on <strong>{{employee.birthDate}}</strong> in <strong>{{employee.birthCity}}</strong>, holder of passport No. <strong>{{employee.passportNumber}}</strong>, currently residing in TUNISIA, {{employee.city}} Tunisia, and holding the position of <strong>{{employee.position}}</strong>.</p>
  <p class="body-text">This invitation is strictly professional in nature and is planned for a period of 30 days, from <strong>{{trip.startDate}}</strong> to <strong>{{trip.endDate}}</strong>. Its purpose is the execution and supervision of engineering studies for the <strong>Grand Paris Line 15</strong> project, carried out by ARX Tunisie on behalf of Arx France.</p>
  <p class="body-text">The presence of <strong>{{employee.civilite}} {{employee.fullName}}</strong> is required to coordinate the studies, attend project meetings, and participate in the client audit meeting with Arx France. This assignment cannot be performed remotely or postponed.</p>
  <p class="body-text">During the stay, <strong>{{employee.civilite}} {{employee.fullName}}</strong> will reside at <strong>{{trip.hotel}}</strong>.</p>
  <p class="body-text">All costs related to the stay in France of <strong>{{employee.civilite}} {{employee.fullName}}</strong> will be fully covered by their employer, <strong>ARX Tunisie</strong>, domiciled at 7, Avenue de Paris, BOUMHEL EL BASSATINE, TUNISIA.</p>
  <p class="body-text">I remain at your disposal for any further information and extend to you, Dear Consul General, my highest regards.</p>
  <div class="signature-block">
    <strong>{{sender.name}}</strong><br>
    {{sender.title}}
  </div>
  <div class="footer-rule"></div>
  <div class="footer">
    <div><strong>ARX Tunisie</strong> &mdash; Civil Engineering Consulting Firm</div>
    <div>Registered Office: 7 Avenue de Paris, BOUMHEL EL BASSATINE, 2097, GOVERNORATE OF BEN AROUS</div>
    <div style="margin-top:1.5mm;">Page 1 of 1</div>
  </div>
</div>
</body>
</html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 1148;
GO

-- ── 4. Egypt's 6 documents, translated to French (lang='fr') ────────────────────

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Attestation de travail standard (CDI/CDD)',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .company-block { text-align: right; } .company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; } .company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; } .doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 16mm; } .doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; } .title-underline { display: block; width: 68%; border: none; border-top: 2px solid #000; margin: 0 auto 10mm; } .body-text { line-height: 1.9; text-align: justify; margin: 3.5mm 0; } .signature-block { margin-top: 14mm; text-align: right; line-height: 2; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="company-block"><div class="company-name-hdr">{{company.name}}</div><div class="company-sub">Bureau d''etudes d''ingenierie</div></div></div><div class="doc-date">Le Caire, {{document.date}}</div><div class="doc-title">Attestation de Travail</div><hr class="title-underline"><p class="body-text">Je soussigne(e), <strong>{{company.dgName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{company.dgCin}}</strong> delivree a {{company.dgCinCity}} le {{company.dgCinDate}}, agissant en qualite de <strong>{{company.dgTitle}}</strong> de <strong>{{company.name}}</strong>,</p><p class="body-text">atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{employee.cin}}</strong> delivree a {{employee.cinCity}} le {{employee.cinDate}}, occupe le poste de <strong>{{employee.position}}</strong> au sein de notre societe depuis le <strong>{{employee.startDateMoisAn}}</strong>, dans le cadre d''un contrat de travail a duree <strong>{{employee.contractDuration}}</strong>.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse(e) pour servir et valoir ce que de droit.</p><div class="signature-block"><p><strong>{{company.name}}</strong></p><p>{{company.dgTitle}}</p><br><br><p><em>{{company.dgName}}</em></p></div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref : {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Code de verification : {{document.verificationCode}}</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 52;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Attestation de salaire indiquant le montant du salaire annuel net (EGP)',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .company-block { text-align: right; } .company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; } .company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; } .doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 16mm; } .doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; } .title-underline { display: block; width: 68%; border: none; border-top: 2px solid #000; margin: 0 auto 10mm; } .body-text { line-height: 1.9; text-align: justify; margin: 3.5mm 0; } .signature-block { margin-top: 14mm; text-align: right; line-height: 2; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="company-block"><div class="company-name-hdr">{{company.name}}</div><div class="company-sub">Bureau d''etudes d''ingenierie</div></div></div><div class="doc-date">Le Caire, {{document.date}}</div><div class="doc-title">Attestation de Salaire</div><hr class="title-underline"><p class="body-text">Je soussigne(e), <strong>{{company.dgName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{company.dgCin}}</strong> delivree a {{company.dgCinCity}} le {{company.dgCinDate}}, agissant en qualite de <strong>{{company.dgTitle}}</strong> de <strong>{{company.name}}</strong>;</p><p class="body-text">atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{employee.cin}}</strong> delivree a {{employee.cinCity}} le {{employee.cinDate}}, employe(e) au sein de notre societe depuis le <strong>{{employee.startDateMoisAn}}</strong>, percoit un Salaire Annuel Net estime a <strong>{{employee.salaireNetAnnuel}} Livres Egyptiennes (EGP)</strong> soit <strong>{{employee.salaireBrutAnnuelEnLettres}}</strong>.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse(e) pour servir et valoir ce que de droit.</p><div class="signature-block"><p><strong>Le {{company.dgTitle}}</strong></p><br><br><p><em>{{company.dgName}}</em></p></div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref : {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Code de verification : {{document.verificationCode}}</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 246;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Attestation declarant l''absence de pret ou avance en cours aupres de l''employeur',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .company-block { text-align: right; } .company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; } .company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; } .doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 16mm; } .doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; } .title-underline { display: block; width: 68%; border: none; border-top: 2px solid #000; margin: 0 auto 10mm; } .body-text { line-height: 1.9; text-align: justify; margin: 3.5mm 0; } .signature-block { margin-top: 14mm; text-align: right; line-height: 2; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="company-block"><div class="company-name-hdr">{{company.name}}</div><div class="company-sub">Bureau d''etudes d''ingenierie</div></div></div><div class="doc-date">Le Caire, {{document.date}}</div><div class="doc-title">Attestation de Non-Benefice de Pret</div><hr class="title-underline"><p class="body-text">Je soussigne(e), <strong>{{employee.civilite}} {{employee.fullName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{employee.cin}}</strong> delivree a {{employee.cinCity}} le {{employee.cinDate}}, employe(e) chez <strong>{{company.name}}</strong> (Bureau d''etudes d''ingenierie) et residant a {{employee.city}},</p><p class="body-text">atteste par la presente, librement et solennellement, ne beneficier a ce jour d''aucun pret, credit en cours, ni d''aucune avance financiere de quelque nature que ce soit de la part de mon employeur, <strong>{{company.name}}</strong>.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse(e) pour servir et valoir ce que de droit.</p><div class="signature-block"><p><strong>Pour le compte de {{company.name}}</strong></p><p>Direction</p><br><br><p><em>Cachet et Signature</em></p></div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref : {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Code de verification : {{document.verificationCode}}</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 440;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Attestation confirmant le passage a un contrat de travail a duree indeterminee',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .company-block { text-align: right; } .company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; } .company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; } .doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 16mm; } .doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; } .title-underline { display: block; width: 68%; border: none; border-top: 2px solid #000; margin: 0 auto 10mm; } .body-text { line-height: 1.9; text-align: justify; margin: 3.5mm 0; } .signature-block { margin-top: 14mm; text-align: right; line-height: 2; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="company-block"><div class="company-name-hdr">{{company.name}}</div><div class="company-sub">Bureau d''etudes d''ingenierie</div></div></div><div class="doc-date">Le Caire, {{document.date}}</div><div class="doc-title">Attestation de Titularisation</div><hr class="title-underline"><p class="body-text">Je soussigne(e), <strong>{{company.dgName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{company.dgCin}}</strong> delivree a {{company.dgCinCity}} le {{company.dgCinDate}}, agissant en qualite de <strong>{{company.dgTitle}}</strong> de <strong>{{company.name}}</strong>,</p><p class="body-text">atteste par la presente que <strong>{{employee.civilite}} {{employee.fullName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{employee.cin}}</strong> delivree a {{employee.cinCity}} le {{employee.cinDate}}, a ete titularise(e) au poste de <strong>{{employee.position}}</strong> au sein de notre societe a compter du <strong>{{employee.titularisationDate}}</strong>.</p><p class="body-text"><strong>{{employee.civilite}} {{employee.fullName}}</strong> beneficie a ce titre d''un contrat de travail a duree indeterminee conformement a la legislation du travail en vigueur.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse(e) pour servir et valoir ce que de droit.</p><div class="signature-block"><p><strong>{{company.name}}</strong></p><p>{{company.dgTitle}}</p><br><br><p><em>{{company.dgName}}</em></p></div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref : {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Code de verification : {{document.verificationCode}}</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 634;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Attestation de domiciliation bancaire du salaire',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .company-block { text-align: right; } .company-name-hdr { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; font-weight: bold; color: #1e3a5f; } .company-sub { font-family: Arial, Helvetica, sans-serif; font-size: 8.5pt; color: #555; margin-top: 1mm; } .doc-date { text-align: right; font-size: 10.5pt; margin-bottom: 16mm; } .doc-title { font-family: Arial, Helvetica, sans-serif; font-size: 14pt; font-weight: bold; text-align: center; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 3mm; } .title-underline { display: block; width: 68%; border: none; border-top: 2px solid #000; margin: 0 auto 10mm; } .body-text { line-height: 1.9; text-align: justify; margin: 3.5mm 0; } .bank-block { margin: 4mm 0 4mm 28mm; line-height: 2.2; } .bank-row { display: flex; gap: 6mm; align-items: baseline; } .bank-label { font-weight: bold; min-width: 18mm; } .signature-block { margin-top: 14mm; text-align: right; line-height: 2; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="company-block"><div class="company-name-hdr">{{company.name}}</div><div class="company-sub">Bureau d''etudes d''ingenierie</div></div></div><div class="doc-date">Le Caire, {{document.date}}</div><div class="doc-title">Attestation de Domiciliation de Salaire</div><hr class="title-underline"><p class="body-text">Je soussigne(e), <strong>{{company.dgName}}</strong>, titulaire de la carte d''identite nationale n&deg; <strong>{{company.dgCin}}</strong> delivree a {{company.dgCinCity}} le {{company.dgCinDate}}, agissant en qualite de <strong>{{company.dgTitle}}</strong> de <strong>{{company.name}}</strong>,</p><p class="body-text">atteste par la presente que le salaire mensuel de <strong>{{employee.civilite}} {{employee.fullName}}</strong>, occupant le poste de <strong>{{employee.position}}</strong> au sein de notre societe depuis le <strong>{{employee.startDate}}</strong>, est vire sur le compte bancaire suivant :</p><div class="bank-block"><div class="bank-row"><span class="bank-label">Banque :</span><span>{{employee.bank}}</span></div><div class="bank-row"><span class="bank-label">N&deg; de compte :</span><span>{{employee.rib}}</span></div><div class="bank-row"><span class="bank-label">IBAN :</span><span>{{employee.iban}}</span></div></div><p class="body-text">Le virement du salaire mensuel est effectue exclusivement sur ce compte.</p><p class="body-text">Cette attestation est delivree a la demande de l''interesse(e) pour servir et valoir ce que de droit.</p><div class="signature-block"><p><strong>{{company.name}}</strong></p><p>{{company.dgTitle}}</p><br><br><p><em>{{company.dgName}}</em></p></div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1 &nbsp;&nbsp;|&nbsp;&nbsp; Ref : {{document.ref}} &nbsp;&nbsp;|&nbsp;&nbsp; Code de verification : {{document.verificationCode}}</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 828;
GO

INSERT INTO [dbo].[document_templates]
    (pays_id, category, lang, name, description, html_content, variables, page_size, sharepoint_location, is_active, created_at)
SELECT pays_id, category, 'fr', name,
    N'Lettre d''invitation professionnelle pour visa France (Consulat de France au Caire)',
    N'<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"><style>* { margin: 0; padding: 0; box-sizing: border-box; } body { font-family: Georgia, ''Times New Roman'', Times, serif; font-size: 11pt; color: #000; } .page { width: 210mm; min-height: 297mm; padding: 18mm 22mm 30mm; position: relative; background: #fff; } .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14mm; } .arx-logo-text { font-family: Arial, Helvetica, sans-serif; font-size: 30pt; font-weight: 900; color: #1e3a5f; line-height: 1; letter-spacing: -1px; } .arx-tagline { font-family: Arial, Helvetica, sans-serif; font-size: 6.5pt; color: #666; letter-spacing: 4px; margin-top: 2mm; } .sender-block { text-align: right; font-size: 10.5pt; line-height: 1.7; } .date-recipient-row { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 10mm; } .doc-date { font-size: 10.5pt; } .recipient-block { text-align: left; font-size: 10.5pt; line-height: 1.7; } .subject-line { margin: 6mm 0 10mm; font-size: 10.5pt; line-height: 1.7; } .salutation { margin-bottom: 6mm; font-size: 11pt; } .body-text { line-height: 1.9; text-align: justify; margin: 4mm 0; font-size: 10.5pt; } .signature-block { margin-top: 14mm; text-align: left; line-height: 2; font-size: 10.5pt; } .footer-rule { position: absolute; bottom: 20mm; left: 22mm; right: 22mm; height: 1px; background: #ccc; } .footer { position: absolute; bottom: 6mm; left: 22mm; right: 22mm; font-family: Arial, Helvetica, sans-serif; font-size: 7pt; color: #888; text-align: center; line-height: 1.7; }</style></head><body><div class="page"><div class="header"><div><div class="arx-logo-text">ARX</div><div class="arx-tagline">S M A R T &nbsp; M I N D S</div></div><div class="sender-block"><strong>{{sender.name}}</strong><br>{{sender.title}}<br>{{sender.address}}<br>{{sender.city}}</div></div><div class="date-recipient-row"><div class="doc-date">{{sender.city}}, {{document.date}}</div><div class="recipient-block"><strong>Consulat General de France au Caire</strong><br>1 Latin America St.,<br>Le Caire, Egypte</div></div><div class="subject-line"><strong>Objet :</strong> Invitation professionnelle pour une reunion de chantier en presentiel<br><strong>Copie :</strong> {{company.name}}</div><p class="salutation">Monsieur le Consul General,</p><p class="body-text">Agissant en qualite de <strong>{{sender.title}}</strong> de la societe <strong>Arx France</strong>, je vous informe avoir l''intention d''inviter en France le ressortissant etranger ci-apres designe : <strong>{{employee.civilite}} {{employee.fullName}}</strong>, ne(e) le <strong>{{employee.birthDate}}</strong> a <strong>{{employee.birthCity}}</strong>, detenteur(trice) du passeport n&deg; <strong>{{employee.passportNumber}}</strong>, residant actuellement en EGYPTE, {{employee.city}}, et exercant la profession de <strong>{{employee.position}}</strong>.</p><p class="body-text">Cette invitation, intervenant dans un cadre strictement professionnel, est prevue pour une duree de 30 jours, du <strong>{{trip.startDate}}</strong> au <strong>{{trip.endDate}}</strong>. Elle a pour objectif la realisation et le suivi des etudes d''execution du projet <strong>Ligne 15 Grand Paris</strong>, realisees par {{company.name}} pour le compte de Arx France.</p><p class="body-text">La presence de <strong>{{employee.civilite}} {{employee.fullName}}</strong> est requise pour coordonner les etudes, assister aux reunions de chantier et a la reunion d''audit avec le client d''Arx France. Cette mission d''etudes ne peut etre effectuee a distance ni etre reportee dans le temps.</p><p class="body-text">Pendant la duree de son sejour, <strong>{{employee.civilite}} {{employee.fullName}}</strong> residera a l''hotel <strong>{{trip.hotel}}</strong>.</p><p class="body-text">Les frais inherents au sejour en France de <strong>{{employee.civilite}} {{employee.fullName}}</strong> seront integralement pris en charge par son employeur, <strong>{{company.name}}</strong>, domicilie au Caire, Egypte.</p><p class="body-text">Je reste a votre entiere disposition pour vous fournir toute precision utile et vous prie de croire, Monsieur le Consul General, en l''assurance de ma respectueuse consideration.</p><div class="signature-block"><strong>{{sender.name}}</strong><br>{{sender.title}}</div><div class="footer-rule"></div><div class="footer"><div><strong>{{company.name}}</strong> &mdash; Bureau d''etudes d''ingenierie</div><div style="margin-top:1.5mm;">Page 1 sur 1</div></div></div></body></html>',
    variables, page_size, sharepoint_location, 1, SYSDATETIMEOFFSET()
FROM [dbo].[document_templates] WHERE id = 1022;
GO
