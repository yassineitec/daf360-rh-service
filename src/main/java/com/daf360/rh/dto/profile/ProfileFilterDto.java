package com.daf360.rh.dto.profile;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ProfileFilterDto {
    private Long      paysId;
    private String    status;        // LifecycleStatus enum value
    private String    department;    // departments.label_fr
    private String    grade;         // grades.label_fr
    private String    contract;      // contract_type value
    private String    search;        // free-text — matches fullName / email via Users join
    /**
     * Inclusives toutes les deux. Elles existaient déjà ici, et l'écran les envoyait déjà —
     * mais `ProfileFilter` côté Angular ne les déclarait pas, `list()` ne les transmettait
     * pas et le contrôleur ne les acceptait pas. Régler une période de recrutement ne
     * changeait donc rien, en silence.
     */
    private LocalDate hireDateFrom;
    private LocalDate hireDateTo;
    /**
     * Inclure les profils qui ne sont PAS dans l'effectif présent : PRE_ONBOARDING,
     * OFFBOARDING, TERMINATED, ARCHIVED.
     *
     * <p>Faux par défaut — la liste montre l'effectif. Ce commutateur existe parce qu'un
     * profil parti doit rester atteignable : rouvrir un offboarding validé, consulter une
     * archive, retrouver quelqu'un qui revient. Sans lui, ces profils n'auraient plus
     * aucune porte d'entrée dans l'application.
     */
    private boolean   includeInactive;
}
