-- =============================================================================
-- V80__fix_document_templates_sharepoint_location_prefix.sql
-- Corrige le sharepoint_location seedé par V78 : il pointait vers
-- "01_HR/01_Contracts-Employment/{employeeFolder}/HR Requests", MAIS l'arborescence
-- réelle SharePoint vit sous un premier niveau "Tunisia/" (confirmé en listant
-- réellement le contenu du site via Graph API 2026-08-16 : "01_HR" n'existe qu'à
-- l'intérieur de "Tunisia/", pas à la racine du site pini-tunisia — la racine
-- contient EGY / General / North Africa-Region / Shared Documents / Tunisia).
-- Sans ce correctif, tout document généré pour un employé tunisien aurait été
-- déposé (ou aurait tenté de créer un nouvel arbre) au mauvais endroit.
--
-- Ciblée sur la valeur exacte posée par V78 uniquement, pour ne jamais écraser une
-- valeur qu'un admin aurait déjà éditée manuellement entre-temps.
-- =============================================================================

UPDATE dt
SET dt.sharepoint_location = 'Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/HR Requests'
FROM [dbo].[document_templates] dt
JOIN [dbo].[pays] p ON p.id = dt.pays_id
WHERE p.iso_code = 'TN'
  AND dt.sharepoint_location = '01_HR/01_Contracts-Employment/{employeeFolder}/HR Requests';
GO
