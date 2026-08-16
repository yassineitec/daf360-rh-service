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
 * Client Microsoft Graph minimal — auth (client credentials) + résolution de site + recherche/
 * création de dossier + upload de fichier. Portée volontairement étroite : pas de suppression,
 * pas de lecture de fichiers existants, pas de gestion multi-site — juste ce qu'il faut pour
 * PdfDocumentService.saveGeneratedDocument().
 *
 * Conçu pour ne JAMAIS faire échouer la génération de document : toute erreur (config absente,
 * auth invalide, réseau, permissions) est loguée et avalée — l'appelant reçoit simplement
 * Optional.empty() et continue avec la copie locale déjà enregistrée (cf. PdfDocumentService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphSharePointService {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.create();

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
        try {
            String folderPath = locationTemplate.replace("{employeeFolder}", employeeFolderName);
            String token  = getAccessToken();
            String siteId = getSiteId(token);
            ensureFolderExists(token, siteId, folderPath);
            String webUrl = uploadFile(token, siteId, folderPath, fileName, content);
            log.info("Document {} déposé sur SharePoint: {}", fileName, webUrl);
            return Optional.ofNullable(webUrl);
        } catch (Exception e) {
            log.warn("Échec de l'upload SharePoint pour {} (copie locale déjà enregistrée, on continue): {}",
                    fileName, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isConfigured() {
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
}
