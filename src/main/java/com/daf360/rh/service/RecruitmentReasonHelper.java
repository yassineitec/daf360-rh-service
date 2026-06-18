package com.daf360.rh.service;

public final class RecruitmentReasonHelper {

    private RecruitmentReasonHelper() {}

    public static String getLabel(String code) {
        if (code == null) return null;
        return switch (code) {
            case "CREATION_POSTE" -> "Création de poste";
            case "REMPLACEMENT"   -> "Remplacement";
            case "ACCROISSEMENT"  -> "Accroissement d'activité";
            default               -> code;
        };
    }
}
