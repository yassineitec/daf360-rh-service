-- V57: Add email_routing_recipients for REQUEST_SUBMITTED so the HR team
-- receives an email when an employee submits a request.
-- Previously the notification was sent via NotificationService.sendToHrManager()
-- (hardcoded). Now it goes through the routing rule so the template is
-- editable from the admin UI.

-- TO: Ressources Humaines (RH) role
INSERT INTO [dbo].[email_routing_recipients] ([routing_rule_id], [role_id], [recipient_field], [is_active])
SELECT rr.[id], r.[id], 'TO', 1
FROM   [dbo].[notification_routing_rules]  rr
JOIN   [dbo].[notification_event_types]    et ON et.[id] = rr.[event_type_id]
JOIN   [dbo].[Roles]                        r  ON r.[frenchName] = 'Ressources Humaines (RH)'
WHERE  et.[event_code] = 'REQUEST_SUBMITTED'
  AND  rr.[pays_id] IS NULL
  AND  NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] err
    WHERE err.[routing_rule_id] = rr.[id] AND err.[role_id] = r.[id]
  );
GO

-- CC: Directeur des Ressources Humaines (DRH) role
INSERT INTO [dbo].[email_routing_recipients] ([routing_rule_id], [role_id], [recipient_field], [is_active])
SELECT rr.[id], r.[id], 'CC', 1
FROM   [dbo].[notification_routing_rules]  rr
JOIN   [dbo].[notification_event_types]    et ON et.[id] = rr.[event_type_id]
JOIN   [dbo].[Roles]                        r  ON r.[frenchName] = 'Directeur des Ressources Humaines (DRH)'
WHERE  et.[event_code] = 'REQUEST_SUBMITTED'
  AND  rr.[pays_id] IS NULL
  AND  NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] err
    WHERE err.[routing_rule_id] = rr.[id] AND err.[role_id] = r.[id]
  );
GO
