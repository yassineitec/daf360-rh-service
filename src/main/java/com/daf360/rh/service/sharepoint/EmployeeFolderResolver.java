package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resout le nom de dossier SharePoint d'un employe ("Prenom NOM" = Users.fullName,
 * nettoye) et detecte les homonymies qui rendraient ce nom de dossier ambigu.
 *
 * Extrait de PdfDocumentService (generation de documents) pour etre reutilise tel
 * quel par EmployeeProfileService (photo de profil, cf. spec 2026-08-18) — meme
 * convention de nommage, meme garde-fou, une seule requete a maintenir plutot que
 * deux copies qui pourraient diverger avec le temps.
 */
@Component
@RequiredArgsConstructor
public class EmployeeFolderResolver {

    private final JdbcTemplate jdbc;

    /** Nettoie un fullName brut ("Abir  ESSAYEM"-style espaces multiples) en nom de
     * dossier utilisable — n'affecte pas la recherche d'un dossier existant, seulement
     * ce qu'on ecrit nous-memes. Retourne null si fullName est null/vide. */
    public String normalize(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().replaceAll("\\s+", " ");
    }

    /** Garde-fou : si plusieurs employes du meme pays partagent exactement le meme
     * fullName (comparaison insensible a la casse), le nom de dossier "Prenom NOM" est
     * ambigu — refuser d'y deposer/lire quoi que ce soit plutot que de risquer de
     * melanger les documents (ou la photo) de deux employes differents. Scope par
     * pays_id : chaque pays a son propre site SharePoint.
     * employeeFolder null → rien a comparer, donc pas ambigu (false) plutot que NPE :
     * le contrat doit rester sur ses pieds seul, sans dependre du garde deja fait par
     * l'appelant actuel (PdfDocumentService), puisqu'un futur second appelant pourrait
     * oublier ce garde. */
    public boolean isAmbiguous(String employeeFolder, Long paysId) {
        if (employeeFolder == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)",
                Integer.class, paysId, employeeFolder.trim());
        return count != null && count > 1;
    }
}
