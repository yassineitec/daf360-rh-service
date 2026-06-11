package com.daf360.rh.domain.enums;

/**
 * Matches the actual values in absences.etatDemande / teletravails.etatDemande / autorisations.etatDemande.
 * Replaces the old LeaveStatus enum (PENDING, MANAGER_APPROVED…) which had no DB counterpart.
 */
public enum DemandeEtat {
    EN_ATTENTE,  // pending — manager and/or HR validation outstanding
    VALIDE,      // fully approved
    REFUSE,      // rejected
    ARCHIVE      // cancelled / archived
}
