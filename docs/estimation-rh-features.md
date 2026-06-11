# Estimation — Fonctionnalités RH DAF360

**Date :** 20 mai 2026
**Projet :** daf360-rh-service (backend Spring Boot) + daf360-shell (frontend Angular)
**Sprint :** 2 semaines — du **26 mai** au **6 juin 2026**
**Équipe :** 2 développeurs en parallèle (1 Backend · 1 Frontend)

---

## Périmètre fonctionnel

### Module 1 — Recrutement
- Création d'un profil candidat
- Création et planification d'entretiens (formulaire : date, type, profil candidat)
- Résultat d'entretien : accepté / refusé + date d'embauche prévisionnelle
- Chaîne d'entretiens multiples (RH → Technique → Manager)
- Suivi de l'offre envoyée (statut : envoyée, acceptée, refusée)
- Notification + mail à l'agent RH lors d'une acceptation d'offre
- Création du profil complet de l'employé depuis le dossier candidat accepté

### Module 2 — Cycle de vie de l'employé
- Statuts successifs : **Période d'essai → Titulaire → Démission**
- Détection automatique de la fin de période d'essai + notification RH
- Notification de passage en statut Titulaire
- Flux de démission avec désactivation du compte

### Module 3 — Gestion des demandes (MVP)
- Création d'une demande par l'employé (type, description, pièces jointes)
- Traitement RH : approbation / rejet + commentaire
- Historique des demandes

---

## Plan de sprint — 2 semaines

> **Convention :** chaque journée = 1 jour/dev.
> Les deux colonnes s'exécutent en parallèle.

| Jour | Backend (Dev 1) | Frontend (Dev 2) |
|------|-----------------|-----------------|
| **J1** — 26/05 | Entités DB : `Candidat`, `Entretien`, `Offre`, migrations + repos | Setup routing RH, page liste candidats (squelette) |
| **J2** — 27/05 | CRUD Candidat + CRUD Entretien + machine à états (statuts) | Formulaire création candidat + formulaire entretien |
| **J3** — 28/05 | Chaîne d'entretiens multiples (endpoint rounds, logique séquentielle) | Vue timeline des rounds d'entretiens |
| **J4** — 29/05 | Gestion offre (envoi, acceptation, refus) + date d'embauche | Interface offre + boutons accepter / refuser |
| **J5** — 30/05 | Notification in-app + mail RH (nouveaux acceptés) · création profil employé depuis candidat | Dashboard RH — liste nouveaux entrants · formulaire profil complet |
| **J6** — 02/06 | Statuts employé (`TRIAL` / `PERMANENT` / `RESIGNED`) + transitions | Affichage statut employé + timeline cycle de vie |
| **J7** — 03/06 | Job planifié : détection fin période d'essai + notification automatique | Notification Titulaire · flux de démission (UI) |
| **J8** — 04/06 | Demandes CRUD + upload documents + traitement RH (approve/reject) | Formulaire demande employé + upload pièces jointes |
| **J9** — 05/06 | Tests unitaires critiques · mail intégration (SMTP) · polish API | Interface traitement RH des demandes · historique |
| **J10** — 06/06 | Intégration end-to-end · correction bugs · revue sécurité (permissions) | Intégration end-to-end · corrections · polish UI |

---

## Récapitulatif chiffré

| Module | Backend | Frontend | Total |
|--------|---------|----------|-------|
| Recrutement | 5j | 5j | **10j** |
| Cycle de vie | 2j | 2j | **4j** |
| Demandes (MVP) | 1.5j | 1.5j | **3j** |
| Intégration / tests / polish | 1.5j | 1.5j | **3j** |
| **TOTAL** | **10j** | **10j** | **20j** |

> Avec 2 développeurs en parallèle sur 10 jours ouvrés = **2 semaines calendaires**.

---

## Hypothèses

- 2 développeurs disponibles à temps plein sur la période
- Design cohérent avec le système existant DAF360 (pas de maquettes séparées)
- Upload fichiers en stockage local (pas S3 / CDN externe)
- Email via SMTP configuré (serveur existant ou Mailtrap en dev)
- Permissions déjà gérées via le système `RolePermissions` existant
- Pas de tests E2E automatisés (Cypress / Playwright) dans ce sprint

## Risques

| Risque | Probabilité | Impact | Mitigation |
|--------|-------------|--------|------------|
| Complexité machine à états recrutement | Moyenne | Haut | Simplifier : 3 statuts max en V1 |
| Intégration mail SMTP en dev | Faible | Moyen | Mailtrap dès J1 |
| Upload documents (taille, format) | Faible | Faible | Limiter à PDF/images, 5 Mo max |
| Débordement J9-J10 | Moyenne | Moyen | Module 3 (demandes) est le premier candidat au report en V2 |

---

## Hors périmètre (V2)

- Signature électronique du contrat
- Onboarding checklist
- Entretien annuel / évaluation de performance
- Gestion des congés et absences
- Tableau de bord analytics RH
