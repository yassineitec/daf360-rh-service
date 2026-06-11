# DAF360 HR — Notifications & Email

## In-app notifications (`[dbo].[notifications]`)

### How they are created

Every time a key HR workflow action occurs, the backend finds all users in the same
`pays_id` who hold a specific permission, then inserts one `notifications` row per user.

```sql
SELECT DISTINCT u.id FROM Users u
JOIN Roles r       ON r.id    = u.role_id
JOIN RolePermissions rp ON rp.role_id = r.id
WHERE rp.permission = ?          -- e.g. 'IT_PROVISIONING'
  AND u.pays_id     = ?          -- same entity as the candidate
  AND (u.isActive = 1 OR u.isActive IS NULL)
-- → insert one notification row for each matching user
```

### Trigger → Target permission → Who receives it

| Trigger | Target permission | Recipients |
|---------|------------------|------------|
| Candidate **accepted** | `IT_PROVISIONING` | All IT managers in that entity |
| Candidate **accepted** | `HR_ONBOARDING` | All HR officers in that entity |
| IT **email submitted** (MS365 account created) | `HR_ONBOARDING` | All HR officers in that entity |
| **Onboarding completed** | Direct `user_id` (new employee) | The new employee only (`module = PORTAL`) |

### How they are served to the frontend

`NotificationController` filters by the caller's `user_id` from the JWT —
users only ever see their own notifications.

| Endpoint | Description |
|----------|-------------|
| `GET    /api/hr/notifications` | List the last 50 notifications, newest first |
| `GET    /api/hr/notifications/unread-count` | Returns `{ count: N }` |
| `PATCH  /api/hr/notifications/{id}/read` | Mark one notification as read |
| `POST   /api/hr/notifications/read-all` | Mark all notifications as read |

### Frontend behaviour

The **notification panel** (`NotificationPanelComponent`) is a slide-in drawer
accessible from the bell icon in the HR shell header. It calls the four endpoints
above via `LifecycleService`.

The **Onboarding nav badge** shows a red count loaded from
`GET /api/hr/onboarding/pending` on shell initialisation.

---

## Email (`MailService`)

### What is implemented

Only **one email** is currently sent: the welcome email to the new employee at the
end of onboarding.

**Triggered by:** `OnboardingService.completeEmployeeProfile()` — Step 5 of 7.

**Content:**

```
To:      {ms365Email}           e.g. alice@arx.ing
Subject: Bienvenue chez ARX — Activez votre compte DAF360

Bonjour {firstName},

Nous sommes ravis de vous accueillir au sein d'ARX.
Votre dossier a été complété avec succès.

Pour accéder à votre espace collaborateur DAF360,
connectez-vous avec votre adresse Microsoft 365 :

  {portalUrl}

Identifiant       : {ms365Email}
Authentification  : Microsoft 365
(aucun mot de passe séparé requis)

Bienvenue dans l'équipe !
L'équipe RH ARX
```

### Two implementations — switched by `mail.enabled`

| Implementation | Active when | Behaviour |
|---------------|-------------|-----------|
| `LogMailServiceImpl` | `mail.enabled=false` *(default)* | Logs the full email body to the console — no actual sending |
| `SmtpMailServiceImpl` | `mail.enabled=true` | Sends via SMTP (Office 365 configured by default) |

### Configuration

```yaml
# application-local.yml or environment variables
mail:
  enabled: ${MAIL_ENABLED:false}
  from:    ${MAIL_FROM:noreply@daf360.com}

spring:
  mail:
    host:     ${MAIL_HOST:smtp.office365.com}
    port:     ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth:            true
      mail.smtp.starttls.enable: true
```

### Other workflow events — in-app notification only (no email)

The following events create in-app notifications but do **not** send emails:

- Candidate acceptance (→ IT team notified in-app)
- IT provisioning completed (→ HR team notified in-app)
- Leave request submitted / approved / rejected
- Employee request approved / rejected
