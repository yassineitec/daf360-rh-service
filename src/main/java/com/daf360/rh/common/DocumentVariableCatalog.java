package com.daf360.rh.common;

import java.util.List;

/**
 * Static catalog of all resolvable {{variable}} tokens for HR document templates.
 * Add new variables here and handle them in DocumentTemplateService.resolveContext().
 */
public final class DocumentVariableCatalog {

    private DocumentVariableCatalog() {}

    public record VariableDef(String key, String labelFr, String group) {}

    public static final List<VariableDef> ALL = List.of(
        // ── Employé ──────────────────────────────────────────────────────────
        new VariableDef("employee.fullName",                   "Nom complet",                       "Employé"),
        new VariableDef("employee.firstName",                  "Prénom",                            "Employé"),
        new VariableDef("employee.lastName",                   "Nom de famille",                    "Employé"),
        new VariableDef("employee.civilite",                   "Civilité (M. / Mme)",               "Employé"),
        new VariableDef("employee.cin",                        "N° CIN",                            "Employé"),
        new VariableDef("employee.cinCity",                    "Ville délivrance CIN",              "Employé"),
        new VariableDef("employee.cinDate",                    "Date délivrance CIN",               "Employé"),
        new VariableDef("employee.grade",                      "Grade",                             "Employé"),
        new VariableDef("employee.position",                   "Poste / Fonction",                  "Employé"),
        new VariableDef("employee.startDate",                  "Date d'embauche (jj mois aaaa)",    "Employé"),
        new VariableDef("employee.startDateMoisAn",            "Date d'embauche (mois + année)",    "Employé"),
        new VariableDef("employee.titularisationDate",         "Date de titularisation",            "Employé"),
        new VariableDef("employee.contractType",               "Type de contrat (code)",            "Employé"),
        new VariableDef("employee.contractDuration",           "Durée du contrat (libellé)",        "Employé"),
        new VariableDef("employee.salary",                     "Salaire net mensuel",               "Employé"),
        new VariableDef("employee.salaireBrutAnnuel",          "Salaire brut annuel",               "Employé"),
        new VariableDef("employee.salaireBrutAnnuelEnLettres", "Salaire brut annuel (en lettres)",  "Employé"),
        new VariableDef("employee.bank",                       "Banque",                            "Employé"),
        new VariableDef("employee.rib",                        "RIB",                               "Employé"),
        new VariableDef("employee.iban",                       "IBAN",                              "Employé"),
        new VariableDef("employee.city",                       "Ville de l'entité",                 "Employé"),
        new VariableDef("employee.email",                      "Email MS365",                       "Employé"),

        // ── Entreprise / DG ───────────────────────────────────────────────────
        new VariableDef("company.name",                        "Nom de l'entreprise",               "Entreprise"),
        new VariableDef("company.dgName",                      "Nom du Directeur Général",          "Entreprise"),
        new VariableDef("company.dgTitle",                     "Titre du DG",                       "Entreprise"),
        new VariableDef("company.dgCin",                       "CIN du DG",                         "Entreprise"),
        new VariableDef("company.dgCinDate",                   "Date CIN du DG",                    "Entreprise"),
        new VariableDef("company.dgCinCity",                   "Ville CIN du DG",                   "Entreprise"),

        // ── Document ──────────────────────────────────────────────────────────
        new VariableDef("document.ref",                        "Référence du document",             "Document"),
        new VariableDef("document.verificationCode",           "Code de vérification",              "Document"),
        new VariableDef("document.date",                       "Date du document (jj mois aaaa)",   "Document"),

        // ── Date ──────────────────────────────────────────────────────────────
        new VariableDef("date.today",                          "Date du jour (jj/mm/aaaa)",         "Date"),
        new VariableDef("date.day",                            "Jour (numérique)",                  "Date"),
        new VariableDef("date.month",                          "Mois (numérique)",                  "Date"),
        new VariableDef("date.monthLabel",                     "Mois (libellé fr)",                 "Date"),
        new VariableDef("date.year",                           "Année",                             "Date")
    );
}
