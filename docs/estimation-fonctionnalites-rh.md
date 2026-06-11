# Estimation — Fonctionnalités RH (DAF360)

> Estimation en jours/homme pour un développeur full-stack.
> Date : 2026-05-25

---

## Module 1 — Recrutement

### Description fonctionnelle

- Création d'un **profil candidat**
- Création d'un **entretien** (formulaire : date, profil candidat, type d'entretien, résultat attendu)
- Résultat de l'entretien : **accepté / refusé** + date d'embauche prévue
- **Rounds multiples** : enchaînement d'entretiens (ex. entretien RH → technique → manager)
- Suivi de l'**offre envoyée** (statut : envoyée, acceptée, refusée)
- **Notification + email** à l'agent RH lorsqu'une offre est acceptée (liste des nouveaux entrants)
- Création du **profil complet** de l'employé à partir du candidat accepté (formulaire de saisie HR)

### Estimation

| Composant | Backend | Frontend |
|---|---|---|
| Entité Candidat (CRUD, profil) | 1.5j | 1.5j |
| Entretien initial (formulaire, date, résultat) | 1.5j | 1.5j |
| Rounds multiples (chaîne d'entretiens) | 2j | 2j |
| Suivi offre (envoi, statut) | 1j | 1j |
| Notification + email HR (nouveaux acceptés) | 2j | 1j |
| Création profil complet depuis candidat accepté | 1.5j | 2j |
| **Sous-total Module 1** | **9.5j** | **9j** |

---

## Module 2 — Cycle de vie de l'employé

### Description fonctionnelle

- Statut **période d'essai** (durée configurable)
- Passage au statut **titulaire** avec notification automatique (fin de période d'essai)
- **Démission** : désactivation du compte employé

### Estimation

| Composant | Backend | Frontend |
|---|---|---|
| Gestion des statuts (TRIAL → PERMANENT) | 1.5j | 1.5j |
| Job planifié : détection fin période d'essai + notification | 1.5j | 0.5j |
| Démission / désactivation employé | 1j | 1j |
| **Sous-total Module 2** | **4j** | **3j** |

---

## Module 3 — Gestion des demandes employé

### Description fonctionnelle

- Création d'une **demande** par l'employé (type, description)
- Possibilité de **joindre des documents**
- Traitement par l'agent RH : **approuver / rejeter**
- Suivi du statut de la demande

### Estimation

| Composant | Backend | Frontend |
|---|---|---|
| Création demande + types | 1.5j | 1.5j |
| Upload / attachement de documents | 2j | 1.5j |
| Traitement RH (approuver / rejeter) | 1j | 1j |
| **Sous-total Module 3** | **4.5j** | **4j** |

---

## Récapitulatif global

| Module | Backend | Frontend | Total |
|---|---|---|---|
| Module 1 — Recrutement | 9.5j | 9j | **18.5j** |
| Module 2 — Cycle de vie | 4j | 3j | **7j** |
| Module 3 — Demandes | 4.5j | 4j | **8.5j** |
| Intégration / tests / polish | — | — | **4j** |
| **TOTAL** | **18j** | **16j** | **~38 jours** |

---

## Hypothèses

- **1 développeur full-stack** travaillant sur un module à la fois (pas de parallélisme)
- Email via **SMTP simple** (pas de service tiers complexe type SendGrid)
- Upload fichiers en **stockage local ou S3** (sans gestion DRM)
- **Tests unitaires** inclus dans l'estimation, pas de tests E2E automatisés
- Design cohérent avec le système existant DAF360 (pas de maquettes à créer from scratch)
- Stack identique au projet actuel : **Spring Boot 4 / Java 17 / SQL Server** (backend) + **Angular 21 Signals standalone** (frontend)

---

## Parallélisation (optionnel)

Avec **2 développeurs** (1 backend + 1 frontend) travaillant en parallèle :

| | Durée calendaire estimée |
|---|---|
| Module 1 — Recrutement | ~10 jours |
| Module 2 — Cycle de vie | ~4 jours |
| Module 3 — Demandes | ~5 jours |
| **Total parallélisé** | **~20–22 jours calendaires** |
