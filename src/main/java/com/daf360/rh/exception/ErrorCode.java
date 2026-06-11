package com.daf360.rh.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    NOT_FOUND                       (HttpStatus.NOT_FOUND,                "Ressource introuvable"),
    ALREADY_EXISTS                  (HttpStatus.CONFLICT,                 "La ressource existe déjà"),
    EMPLOYEE_NOT_FOUND              (HttpStatus.NOT_FOUND,                "Profil employé introuvable"),
    LIFECYCLE_TRANSITION_INVALID    (HttpStatus.UNPROCESSABLE_CONTENT,     "Transition de statut interdite"),
    LEAVE_BALANCE_INSUFFICIENT      (HttpStatus.UNPROCESSABLE_CONTENT,     "Solde de congé insuffisant pour cette demande"),
    LEAVE_BALANCE_EXCEEDED          (HttpStatus.UNPROCESSABLE_CONTENT,     "Solde de congé insuffisant"),
    LEAVE_BALANCE_NOT_FOUND         (HttpStatus.NOT_FOUND,                "Solde de congé introuvable"),
    LEAVE_OVERLAP                   (HttpStatus.CONFLICT,                 "Un congé approuvé ou en attente chevauche cette période"),
    ABSENCE_NOT_FOUND               (HttpStatus.NOT_FOUND,                "Demande de congé introuvable"),
    TELETRAVAIL_NOT_FOUND           (HttpStatus.NOT_FOUND,                "Demande de télétravail introuvable"),
    AUTORISATION_NOT_FOUND          (HttpStatus.NOT_FOUND,                "Demande d'autorisation introuvable"),
    INVALID_TRANSITION              (HttpStatus.UNPROCESSABLE_CONTENT,     "Transition de statut invalide"),
    DOCUMENT_TYPE_UNSUPPORTED       (HttpStatus.BAD_REQUEST,              "Type de fichier non supporté (PDF, JPG, PNG uniquement)"),
    DOCUMENT_SIZE_EXCEEDED          (HttpStatus.BAD_REQUEST,              "Fichier trop volumineux (max 10 Mo)"),
    REGIME_NOT_FOUND                (HttpStatus.NOT_FOUND,                "Régime horaire introuvable"),
    REQUEST_NOT_FOUND               (HttpStatus.NOT_FOUND,                "Demande introuvable"),
    REQUEST_TYPE_NOT_FOUND          (HttpStatus.NOT_FOUND,                "Type de demande introuvable"),
    REQUEST_DUPLICATE               (HttpStatus.CONFLICT,                 "Une demande du même type est déjà en cours"),
    REQUEST_CANNOT_CANCEL           (HttpStatus.UNPROCESSABLE_CONTENT,     "Seules les demandes SUBMITTED peuvent être annulées"),
    REQUEST_WRONG_STATUS            (HttpStatus.UNPROCESSABLE_CONTENT,     "Statut incompatible avec cette action"),
    DOCUMENT_GENERATION_FAILED      (HttpStatus.INTERNAL_SERVER_ERROR,    "Échec de la génération du document"),
    PARAMETER_NOT_FOUND             (HttpStatus.NOT_FOUND,                "Paramètre introuvable"),
    HOLIDAY_NOT_FOUND               (HttpStatus.NOT_FOUND,                "Jour férié introuvable"),
    ROLE_NOT_FOUND                  (HttpStatus.NOT_FOUND,                "Rôle introuvable"),
    PERMISSION_NOT_ALLOWED          (HttpStatus.BAD_REQUEST,              "Permission non autorisée par le contrat de la base"),
    CANDIDATE_NOT_FOUND             (HttpStatus.NOT_FOUND,                "Candidat introuvable"),
    CANDIDATE_EMAIL_DUPLICATE       (HttpStatus.CONFLICT,                 "Un candidat avec cet email existe déjà"),
    CANDIDATE_STATUS_INVALID        (HttpStatus.UNPROCESSABLE_CONTENT,    "Action impossible : statut du candidat incompatible"),
    IT_PROVISIONING_NOT_FOUND       (HttpStatus.NOT_FOUND,                "Dossier de provisioning introuvable"),
    IT_EMAIL_ALREADY_IN_USE         (HttpStatus.CONFLICT,                 "Email Microsoft 365 déjà utilisé dans le système"),
    IT_AD_ACCOUNT_REQUIRED          (HttpStatus.UNPROCESSABLE_CONTENT,    "Le compte Active Directory doit être créé avant de finaliser le provisioning"),
    IT_EMAIL_REQUIRED               (HttpStatus.UNPROCESSABLE_CONTENT,    "L'email Microsoft 365 doit être soumis avant de finaliser"),
    IT_PAYS_NOT_FOUND               (HttpStatus.NOT_FOUND,                "Pays introuvable pour la génération de l'identifiant"),
    IT_COLLABORATEUR_ROLE_NOT_FOUND (HttpStatus.INTERNAL_SERVER_ERROR,    "Rôle Collaborateur introuvable — vérifiez la table Roles"),
    ONBOARDING_STATUS_INVALID       (HttpStatus.UNPROCESSABLE_CONTENT, "Le candidat n'est pas dans un état compatible avec l'onboarding"),
    ONBOARDING_USER_NOT_CREATED     (HttpStatus.UNPROCESSABLE_CONTENT, "L'email Microsoft 365 doit être soumis avant de compléter l'onboarding"),
    ONBOARDING_PROFILE_EXISTS       (HttpStatus.CONFLICT,              "Un profil employé existe déjà pour ce candidat"),
    BUSINESS_RULE_VIOLATION         (HttpStatus.UNPROCESSABLE_CONTENT,    "Règle métier non respectée"),
    FORBIDDEN                       (HttpStatus.FORBIDDEN,                "Accès refusé"),
    INTERNAL_ERROR                  (HttpStatus.INTERNAL_SERVER_ERROR,    "Erreur interne — veuillez réessayer");

    private final HttpStatus status;
    private final String     defaultMessage;
}
