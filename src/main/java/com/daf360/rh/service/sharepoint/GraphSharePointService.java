package com.daf360.rh.service.sharepoint;

import com.daf360.rh.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Client Microsoft Graph minimal — auth (client credentials) + résolution de site +
 * recherche/création de dossier + upload/téléchargement/suppression de fichier.
 * Portée volontairement étroite : pas de gestion multi-site ; la suppression se
 * limite à un best-effort par chemin complet (pas de suppression récursive de
 * dossier, pas de corbeille/restauration). Deux consommateurs :
 * PdfDocumentService.saveGeneratedDocument() (upload uniquement) et
 * EmployeeProfileService (photo de profil — upload, téléchargement pour le cache
 * local auto-réparateur, et suppression des extensions obsolètes lors d'un
 * re-upload dans un autre format, cf. spec 2026-08-18).
 *
 * Conçu pour ne JAMAIS faire échouer l'appelant : toute erreur (config absente,
 * auth invalide, réseau, permissions, fichier introuvable) est loguée et avalée —
 * l'appelant reçoit simplement Optional.empty() (ou, pour la suppression, ne
 * reçoit rien du tout) et retombe sur son propre comportement de repli (copie
 * locale déjà enregistrée pour l'upload ; 404 pour le téléchargement ; fichier
 * obsolète laissé en place pour la suppression).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphSharePointService {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                    java.net.http.HttpClient.newBuilder()
                            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                            .build()))
            .build();

    private volatile String  cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private volatile String  cachedSiteId;

    /**
     * Résout {employeeFolder} dans locationTemplate, s'assure que ce dossier existe (le crée sinon)
     * et y dépose fileName. Ne lance jamais d'exception — retourne le webUrl SharePoint en cas de
     * succès, Optional.empty() sinon (config absente, échec réseau/auth, etc.).
     */
    public Optional<String> uploadDocument(String locationTemplate, String employeeFolderName,
                                            String fileName, byte[] content) {
        if (!isConfigured()) {
            log.debug("SharePoint non configuré (tenant/client id vide) — upload ignoré pour {}", fileName);
            return Optional.empty();
        }
        if (locationTemplate == null || locationTemplate.isBlank()) {
            log.debug("Aucun sharepoint_location configuré pour cette maquette — upload ignoré pour {}", fileName);
            return Optional.empty();
        }
        return uploadToFolder(
                locationTemplate.replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, employeeFolderName),
                fileName, content);
    }

    /**
     * Same upload, for a caller that already holds the final folder path.
     *
     * <p>Added because {@code SharePointResolver} hands back a resolved path rather than a
     * template plus a name: making its callers re-synthesise a template just to have this
     * method substitute the token straight back out would be a round trip through a format
     * neither side wants. {@link #uploadDocument} now delegates here, so the folder matching
     * and mkdir -p behaviour below is shared rather than duplicated.
     */
    public Optional<String> uploadToFolder(String folderPath, String fileName, byte[] content) {
        if (!isConfigured()) {
            log.debug("SharePoint non configuré (tenant/client id vide) — upload ignoré pour {}", fileName);
            return Optional.empty();
        }
        if (folderPath == null || folderPath.isBlank()) {
            log.debug("Aucun dossier cible — upload ignoré pour {}", fileName);
            return Optional.empty();
        }
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            // Match folders that already exist but are spelled differently (spacing, case,
            // accents) before deciding to create anything — otherwise we file into a duplicate
            // of HR's folder instead of into HR's folder. Costs nothing on the common path:
            // resolveLeniently() returns immediately when the literal path is there.
            String resolved = resolveLeniently(token, siteId, folderPath);
            ensureFolderExists(token, siteId, resolved);
            String webUrl = uploadFile(token, siteId, resolved, fileName, content);
            log.info("Document {} déposé sur SharePoint: {}", fileName, webUrl);
            return Optional.ofNullable(webUrl);
        } catch (Exception e) {
            log.warn("Échec de l'upload SharePoint pour {} (copie locale déjà enregistrée, on continue): {}",
                    fileName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Télécharge le contenu d'un fichier existant sur SharePoint (chemin complet,
     * dossier(s) + nom de fichier). Utilisé par la mise en cache locale
     * auto-réparatrice de la photo de profil (EmployeeProfileService.servePhoto)
     * quand le cache local est vide. Ne lance jamais d'exception — Optional.empty()
     * si le fichier n'existe pas, si SharePoint n'est pas configuré, ou en cas
     * d'échec réseau/auth/permissions.
     */
    public Optional<byte[]> downloadFile(String path) {
        if (!isConfigured()) {
            log.debug("SharePoint non configuré (tenant/client id vide) — téléchargement ignoré pour {}", path);
            return Optional.empty();
        }
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            try {
                return Optional.ofNullable(fetch(token, siteId, path));
            } catch (HttpClientErrorException.NotFound firstMiss) {
                // The literal path is not there. It may still exist under a differently
                // spelled folder ("Abir  ESSAYEM" vs "Abir ESSAYEM"), so resolve the folder
                // part against what is really in the tree and try once more. Only then is
                // the file genuinely absent.
                int slash = path.lastIndexOf('/');
                if (slash <= 0) return Optional.empty();
                String resolved = resolveLeniently(token, siteId, path.substring(0, slash));
                String retryPath = resolved + path.substring(slash);
                if (retryPath.equals(path)) return Optional.empty();
                try {
                    return Optional.ofNullable(fetch(token, siteId, retryPath));
                } catch (HttpClientErrorException.NotFound stillMissing) {
                    return Optional.empty();
                }
            }
        } catch (Exception e) {
            log.warn("Échec du téléchargement SharePoint pour {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private byte[] fetch(String token, String siteId, String path) {
        return restClient.get()
                .uri(GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + path + ":/content")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(byte[].class);
    }

    /**
     * Supprime un fichier existant sur SharePoint (chemin complet), si présent.
     * Best-effort et silencieux : utilisé pour nettoyer les anciennes extensions
     * d'une photo de profil avant d'en déposer une nouvelle (cf. mirrorPhotoToSharePoint
     * dans EmployeeProfileService) — un fichier absent, ou toute erreur réseau/auth,
     * est traité comme un no-op, jamais une exception.
     */
    public void deleteFileIfExists(String path) {
        if (!isConfigured()) return;
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            String url = GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + path;
            restClient.delete()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound notFound) {
            // already absent — nothing to do
        } catch (Exception e) {
            log.warn("Échec de la suppression SharePoint pour {}: {}", path, e.getMessage());
        }
    }

    /**
     * Whether Graph credentials are present at all.
     *
     * <p>Public so {@code SharePointResolver} can report UNAVAILABLE ("the integration is off
     * on this deployment, fix an env variable") instead of FOLDER_MISSING ("go hunt for a
     * folder in SharePoint"). Those two sent an operator in completely different directions,
     * and every lookup here answers Optional.empty() for both.
     */
    public boolean isConfigured() {
        return notBlank(appProperties.getMsGraphTenantId())
                && notBlank(appProperties.getMsGraphClientId())
                && notBlank(appProperties.getMsGraphClientSecret());
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Cache en mémoire, renouvelé 60s avant expiration réelle — un token Graph dure ~1h,
     * pas la peine d'en redemander un à chaque document généré. */
    private synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", appProperties.getMsGraphClientId());
        form.add("client_secret", appProperties.getMsGraphClientSecret());
        form.add("scope", "https://graph.microsoft.com/.default");

        String url = "https://login.microsoftonline.com/" + appProperties.getMsGraphTenantId()
                + "/oauth2/v2.0/token";
        TokenResponse resp = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (resp == null || resp.accessToken() == null) {
            throw new IllegalStateException("Réponse de jeton vide depuis Microsoft identity platform");
        }
        cachedToken = resp.accessToken();
        int expiresIn = resp.expiresIn() != null ? resp.expiresIn() : 3600;
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        return cachedToken;
    }

    /** Le site SharePoint est fixe (config app) — résolu une seule fois par démarrage d'app. */
    private synchronized String getSiteId(String token) {
        if (cachedSiteId != null) return cachedSiteId;
        String url = GRAPH_BASE + "/sites/" + appProperties.getSharepointSiteHostname()
                + ":" + appProperties.getSharepointSitePath();
        SiteResponse site = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(SiteResponse.class);
        if (site == null || site.id() == null) {
            throw new IllegalStateException("Impossible de résoudre le site SharePoint");
        }
        cachedSiteId = site.id();
        return cachedSiteId;
    }

    /** GET sur le chemin complet d'abord (cas courant : le dossier employé existe déjà en entier,
     * une seule requête suffit). S'il n'existe pas, on descend segment par segment ("mkdir -p") en
     * créant chaque niveau manquant sous son parent — nécessaire car sharepoint_location peut
     * pointer 3-4 niveaux sous la racine (ex. "01_HR/01_Contracts-Employment/{employeeFolder}/HR
     * Requests") et rien ne garantit que tous ces niveaux existent déjà (nouvel employé jamais
     * encore présent dans l'arborescence SharePoint, sous-dossier manquant, etc.) — un create
     * sous un parent lui-même absent échouerait sinon en 404. Une 409 sur un segment (créé
     * entre-temps par un appel concurrent) est traitée comme un succès. */
    private void ensureFolderExists(String token, String siteId, String folderPath) {
        if (folderExists(token, siteId, folderPath)) {
            return; // chemin complet déjà présent — cas courant, une seule requête
        }

        String[] segments = folderPath.split("/");
        StringBuilder builtPath = new StringBuilder();
        for (String segment : segments) {
            String parentPath = builtPath.toString();
            if (builtPath.length() > 0) builtPath.append('/');
            builtPath.append(segment);

            if (folderExists(token, siteId, builtPath.toString())) {
                continue; // ce niveau existe déjà, passe au suivant
            }

            String createUrl = parentPath.isEmpty()
                    ? GRAPH_BASE + "/sites/" + siteId + "/drive/root/children"
                    : GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + parentPath + ":/children";

            Map<String, Object> body = Map.of(
                    "name", segment,
                    "folder", Map.of(),
                    "@microsoft.graph.conflictBehavior", "fail"
            );
            try {
                restClient.post()
                        .uri(createUrl)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (HttpClientErrorException.Conflict conflict) {
                // créé entre notre GET et ce POST (course concurrente) — le dossier existe, on continue.
            }
        }
    }

    /**
     * Rewrites {@code folderPath} so every segment that ALREADY exists in SharePoint uses
     * the name SharePoint actually has, comparing case- and whitespace-insensitively
     * ({@link SharePointPaths#sameSegment}). Segments with no match are left as asked, for
     * {@link #ensureFolderExists} to create.
     *
     * <p>Why this exists: {@code Users.fullName} and the real tree disagree on spacing —
     * the live folder is {@code "Abir  ESSAYEM"} (two spaces) while the resolved employee
     * folder name is {@code "Abir ESSAYEM"} (one). A literal lookup missed it, so mkdir -p
     * created a second folder next to HR's, and the two then diverged invisibly.
     *
     * <p>Returns the input unchanged if the whole path already resolves literally (the
     * common case, one request) or if anything goes wrong — this is a best-effort
     * improvement on the path, never a reason to fail an upload.
     */
    private String resolveLeniently(String token, String siteId, String folderPath) {
        try {
            if (folderExists(token, siteId, folderPath)) {
                return folderPath; // fast path: exactly as configured, nothing to correct
            }
            StringBuilder resolved = new StringBuilder();
            for (String wanted : folderPath.split("/")) {
                if (wanted.isBlank()) continue;
                String parent = resolved.toString();
                // ALL matches, not the first one. Matching folds case, spacing and accents
                // (SharePointPaths.fold), so two real folders can now match the same wanted
                // segment — "Kods CHERIF" and "Kods CHÉRIF" side by side would. Picking
                // either would be a coin toss over whose documents get served, so an
                // ambiguous level keeps the literal name and lets the caller fail cleanly.
                java.util.List<String> matches = childrenOf(token, siteId, parent).stream()
                        .filter(name -> SharePointPaths.sameSegment(name, wanted))
                        .toList();
                if (matches.size() > 1) {
                    log.warn("Segment SharePoint '{}' ambigu sous '{}' — {} dossiers y " +
                             "correspondent ({}), aucun choisi", wanted, parent, matches.size(),
                            String.join(", ", matches));
                }
                String actual = matches.size() == 1 ? matches.get(0) : wanted;
                if (!actual.equals(wanted)) {
                    log.info("Segment SharePoint '{}' resolu en '{}' (dossier existant, " +
                             "orthographe differente) sous '{}'", wanted, actual, parent);
                }
                if (resolved.length() > 0) resolved.append('/');
                resolved.append(actual);
            }
            return resolved.toString();
        } catch (Exception e) {
            log.debug("Resolution indulgente impossible pour {} ({}), chemin litteral conserve",
                    folderPath, e.getMessage());
            return folderPath;
        }
    }

    /**
     * Names of the immediate children of a folder ({@code ""} = drive root), following
     * {@code @odata.nextLink} so a folder with more children than one page — the Tunisian
     * employee list is already ~100 — is never silently truncated into a "no match" that
     * would create a duplicate folder.
     */
    private java.util.List<String> childrenOf(String token, String siteId, String parentPath) {
        return listChildren(token, siteId, parentPath).stream().map(ChildItem::name).toList();
    }

    /**
     * Whether a folder exists, after lenient resolution of its spelling. Used by
     * {@code SharePointResolver} to tell FOLDER_MISSING apart from "folder is there but
     * empty" — two outcomes that look identical through {@link #listFiles} and need very
     * different messages in the admin panel.
     *
     * <p>False when SharePoint is unconfigured or the lookup fails, same as everything else
     * here: a caller can never distinguish "absent" from "could not look", and must not need
     * to.
     */
    public boolean folderExists(String folderPath) {
        if (!isConfigured() || folderPath == null || folderPath.isBlank()) return false;
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            return folderExists(token, siteId, resolveLeniently(token, siteId, folderPath));
        } catch (Exception e) {
            log.warn("Echec du test d'existence SharePoint pour {}: {}", folderPath, e.getMessage());
            return false;
        }
    }

    /**
     * Immediate SUBFOLDER names of a folder ({@code ""} = drive root), sorted descending so
     * year folders come back newest first. Empty when the folder is missing, SharePoint is
     * unconfigured, or anything fails — same posture as {@link #listFiles}.
     *
     * <p>Two callers, both of which need to see the tree rather than guess at it: the
     * year level of a year-scoped kind ({@code .../01_Pay-Slip/{year}}), and the admin
     * folder browser, which exists so a path can be picked in the app instead of hand-typed
     * from a SharePoint tab.
     *
     * <p>Descending order is a naming assumption that happens to hold for {@code yyyy}
     * folders and is harmless elsewhere; callers that care about a real chronology should
     * parse the names rather than trust this.
     */
    public java.util.List<String> listFolders(String folderPath) {
        if (!isConfigured()) return java.util.List.of();
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            String resolved = folderPath == null || folderPath.isBlank()
                    ? "" : resolveLeniently(token, siteId, folderPath);
            return listChildren(token, siteId, resolved).stream()
                    .filter(item -> item.folder() != null && item.name() != null)
                    .map(ChildItem::name)
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        } catch (Exception e) {
            log.warn("Echec du listage des dossiers SharePoint de {}: {}", folderPath, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Files in a folder, newest first, folders excluded. Empty when the folder is missing,
     * SharePoint is unconfigured, or anything fails — callers treat "no files" and "could
     * not look" the same way.
     *
     * <p>Exists because a file put there BY HAND has a name we cannot guess: the profile
     * photo self-heal used to probe only the three names its own mirror writes
     * ({@code Photo.jpg/.png/.webp}), so an HR-uploaded portrait was invisible and the
     * avatar 404'd with the file sitting right there.
     */
    public java.util.List<RemoteFile> listFiles(String folderPath) {
        if (!isConfigured()) return java.util.List.of();
        try {
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            String resolved = resolveLeniently(token, siteId, folderPath);
            return listChildren(token, siteId, resolved).stream()
                    .filter(item -> item.folder() == null && item.name() != null)
                    .sorted(java.util.Comparator.comparing(
                            ChildItem::lastModifiedDateTime,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .map(item -> new RemoteFile(item.name(), item.lastModifiedDateTime(),
                            item.size(), item.webUrl()))
                    .toList();
        } catch (Exception e) {
            log.warn("Échec du listage SharePoint de {}: {}", folderPath, e.getMessage());
            return java.util.List.of();
        }
    }

    /** One file in a SharePoint folder. {@code lastModified} is the raw ISO-8601 Graph value. */
    /**
     * @param lastModified the raw ISO-8601 Graph value
     * @param sizeBytes    null when Graph did not report it. Selected because the documents tab
     *                     shows it — a list of names with no size gives no clue whether a row is
     *                     a real scan or an empty placeholder someone created by mistake.
     * @param webUrl       the SharePoint page for the item, for an "open in SharePoint" link.
     *                     Never used to fetch content: that goes through this app, so the
     *                     permission check stays ours rather than the viewer's.
     */
    public record RemoteFile(String name, String lastModified, Long sizeBytes, String webUrl) {}

    private java.util.List<ChildItem> listChildren(String token, String siteId, String parentPath) {
        String select = "?$select=name,folder,lastModifiedDateTime,size,webUrl&$top=200";
        String url = parentPath.isEmpty()
                ? GRAPH_BASE + "/sites/" + siteId + "/drive/root/children" + select
                : GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + parentPath
                  + ":/children" + select;
        java.util.List<ChildItem> items = new java.util.ArrayList<>();
        while (url != null) {
            ChildrenResponse page;
            try {
                page = restClient.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(ChildrenResponse.class);
            } catch (HttpClientErrorException.NotFound missing) {
                return items; // parent itself absent — nothing to list or match against
            }
            if (page == null || page.value() == null) break;
            items.addAll(page.value());
            url = page.nextLink();
        }
        return items;
    }

    private boolean folderExists(String token, String siteId, String path) {
        String getUrl = GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + path;
        try {
            restClient.get()
                    .uri(getUrl)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound notFound) {
            return false;
        }
    }

    /** Upload simple (PUT ...:/content) — suffisant pour des PDF générés, toujours bien en
     * dessous des 4 Mo au-delà desquels Graph exige une session d'upload en plusieurs morceaux. */
    private String uploadFile(String token, String siteId, String folderPath,
                               String fileName, byte[] content) {
        String targetPath = folderPath + "/" + fileName;
        String url = GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + targetPath + ":/content";
        DriveItemResponse item = restClient.put()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content)
                .retrieve()
                .body(DriveItemResponse.class);
        return item != null ? item.webUrl() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Integer expiresIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiteResponse(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DriveItemResponse(String id, String name, String webUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChildrenResponse(
            java.util.List<ChildItem> value,
            @JsonProperty("@odata.nextLink") String nextLink) {}

    /** {@code folder} is Graph's folder facet — present on folders, absent on files. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChildItem(String name, Map<String, Object> folder, String lastModifiedDateTime,
                             Long size, String webUrl) {}
}
