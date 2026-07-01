-- =============================================================================
-- V36__fix_request_notification_templates.sql
-- Updates REQUEST_APPROVED and REQUEST_REJECTED in-app body templates to
-- include the {requestType} placeholder, so notifications read
-- "Votre demande de Attestation de travail a été approuvée."
-- instead of the generic static text seeded in V12.
-- =============================================================================

USE [DAF360_HR];

UPDATE rr
SET    rr.inapp_body_template = N'Votre demande de {requestType} a été approuvée.'
FROM   notification_routing_rules  rr
JOIN   notification_event_types    et ON et.id = rr.event_type_id
WHERE  et.event_code = 'REQUEST_APPROVED'
  AND  rr.inapp_body_template = N'Votre demande a ete approuvee.';

UPDATE rr
SET    rr.inapp_body_template = N'Votre demande de {requestType} a été rejetée. {comment}'
FROM   notification_routing_rules  rr
JOIN   notification_event_types    et ON et.id = rr.event_type_id
WHERE  et.event_code = 'REQUEST_REJECTED'
  AND  rr.inapp_body_template = N'Votre demande a ete rejetee.';

PRINT 'V36 complete — REQUEST_APPROVED and REQUEST_REJECTED templates updated.';
