package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeFolderResolver {

    private final JdbcTemplate jdbc;

    /**
     * Everything needed to build a SharePoint path for one employee: the per-country
     * employee-folder root (already with its {@code {employeeFolder}} placeholder) and the
     * resolved folder name to substitute into it.
     */
    public record EmployeeFolder(String locationTemplate, String employeeFolder) {

        /** The employee's own folder, placeholder substituted. */
        public String basePath() {
            return locationTemplate.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, employeeFolder);
        }

        /** {@link #basePath()} plus one subfolder. */
        public String subPath(String subfolder) {
            return SharePointPaths.join(basePath(), subfolder);
        }
    }

    /**
     * Resolves where this employee's files belong, or null when SharePoint must be skipped
     * for them — no location configured for their country, no usable name, or a name shared
     * with a colleague in the same country.
     *
     * <p>The country's root comes from {@code pays.photo_sharepoint_location}. That column is
     * named for its first consumer but holds the whole path down to the employee folder, so
     * {@link SharePointPaths#employeeFolderBase} recovers the shared part and each caller
     * appends its own leaf. See that method for why this beats adding a second column.
     *
     * <p>Was private in {@code EmployeeProfileService}; moved here so the photo mirror and the
     * document mirror cannot drift apart on the two rules that matter — which countries are
     * wired up, and which employees are too ambiguous to file.
     */
    public EmployeeFolder resolve(Long paysId, Long userId) {
        if (paysId == null || userId == null) return null;

        String configured = jdbc.queryForObject(
                "SELECT photo_sharepoint_location FROM [dbo].[pays] WHERE id = ?",
                String.class, paysId);
        String base = SharePointPaths.employeeFolderBase(configured);
        if (base == null) return null;

        String fullName = jdbc.queryForObject(
                "SELECT fullName FROM [dbo].[Users] WHERE id = ?", String.class, userId);
        String employeeFolder = normalize(fullName);
        if (employeeFolder == null) return null;

        if (isAmbiguous(employeeFolder, paysId)) {
            log.warn("Plusieurs employes partagent le nom de dossier SharePoint '{}' (pays {}) — " +
                     "operation ignoree par securite", employeeFolder, paysId);
            return null;
        }
        return new EmployeeFolder(base, employeeFolder);
    }

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
