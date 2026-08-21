# Profile Photo SharePoint Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **STANDING RULE — DO NOT COMMIT OR PUSH.** The user has an active, standing instruction not to commit or push anything in this repo without explicit go-ahead. Every task below ends with a "mark task complete" step instead of a `git commit` step. Stage nothing, commit nothing, push nothing — implement and stop.

**Goal:** Mirror employee profile photos to the employee's SharePoint folder on upload, and make the local disk cache self-healing (re-fetch from SharePoint on a cache miss), without changing the public API contract or any frontend code.

**Architecture:** Extends the existing `GraphSharePointService` (used today only for HR document generation) with a new `downloadFile()` method, extracts the employee-name-to-SharePoint-folder resolution logic (currently private/duplicated-in-waiting inside `PdfDocumentService`) into a small shared `EmployeeFolderResolver`, and wires both into `EmployeeProfileService.uploadPhoto`/`servePhoto`. Local disk remains the fast-path read source; SharePoint becomes the durable source a cache-miss can self-heal from. Everything SharePoint-related stays best-effort — a failure or missing config never blocks the primary local read/write.

**Tech Stack:** Spring Boot (Java 21+), Spring Data JPA, `JdbcTemplate` (for the non-JPA `pays`/`Users` tables), `RestClient` (Microsoft Graph calls), JUnit 5 + Mockito + AssertJ.

**Full design spec:** `docs/superpowers/specs/2026-08-18-profile-photo-sharepoint-design.md`

---

## Task 1: Migration — new SharePoint columns

**Files:**
- Create: `src/main/resources/db/seed/V77__employee_profile_photo_sharepoint.sql`

- [ ] **Step 1: Write the migration file**

```sql
-- =============================================================================
-- V77__employee_profile_photo_sharepoint.sql
-- Colonnes nécessaires pour mirorer la photo de profil employé vers SharePoint
-- (cf. docs/superpowers/specs/2026-08-18-profile-photo-sharepoint-design.md).
--
-- pays.photo_sharepoint_location : même convention que document_templates.
-- sharepoint_location ({employeeFolder} = espace réservé littéral, résolu au
-- moment de l'upload/du téléchargement, PAS un jeton Handlebars). Valeur par
-- défaut posée UNIQUEMENT pour la Tunisie — seule structure SharePoint réelle
-- confirmée à ce jour. Le sous-dossier "Identity Documents" est PROVISOIRE :
-- pas encore confirmé contre l'arborescence réelle (cf. mémoire
-- project-sharepoint-architecture) — à corriger ici si le nom réel diffère.
-- Tous les autres pays restent NULL : pas de structure réelle connue pour eux,
-- mieux vaut vide que deviné (même politique que V74).
--
-- employee_profiles.photo_sharepoint_url : trace du lien SharePoint obtenu si
-- l'upload a réussi — même rôle que generated_documents.sharepoint_url. NULL
-- est la valeur normale et attendue tant que SharePoint n'est pas configuré,
-- que le pays n'a pas de photo_sharepoint_location, ou que le nom d'employé
-- est ambigu.
--
-- Idempotente : ADD gardé par IF NOT EXISTS ; l'UPDATE ne touche que les
-- lignes encore NULL (ne réécrase jamais une valeur déjà éditée à la main).
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'pays' AND COLUMN_NAME = 'photo_sharepoint_location'
)
    ALTER TABLE [dbo].[pays] ADD [photo_sharepoint_location] NVARCHAR(500) NULL;
GO

UPDATE [dbo].[pays]
SET photo_sharepoint_location = 'Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents'
WHERE iso_code = 'TN' AND photo_sharepoint_location IS NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'photo_sharepoint_url'
)
    ALTER TABLE [dbo].[employee_profiles] ADD [photo_sharepoint_url] NVARCHAR(1000) NULL;
GO
```

- [ ] **Step 2: Apply it to the local `DAF360_HR` database by hand**

This project's `db/seed/V*.sql` files are **not** Flyway-managed (no `flyway_schema_history` table exists) — apply by hand, same as V74–V76. Run each `GO`-separated batch against the local SQL Server instance (`localhost:1433`, db `DAF360_HR`).

- [ ] **Step 3: Verify via a fresh read-back**

```sql
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE (TABLE_NAME = 'pays' AND COLUMN_NAME = 'photo_sharepoint_location')
   OR (TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'photo_sharepoint_url');

SELECT id, iso_code, photo_sharepoint_location FROM [dbo].[pays] WHERE iso_code = 'TN';
```
Expected: both columns listed; the Tunisia row shows the seeded template string; every other row's `photo_sharepoint_location` is `NULL`.

- [ ] **Step 4: Mark task complete** (do NOT commit — see standing rule above)

---

## Task 2: Extract `EmployeeFolderResolver` and switch `PdfDocumentService` to use it

**Files:**
- Create: `src/main/java/com/daf360/rh/service/sharepoint/EmployeeFolderResolver.java`
- Test: `src/test/java/com/daf360/rh/sharepoint/EmployeeFolderResolverTest.java`
- Modify: `src/main/java/com/daf360/rh/service/pdf/PdfDocumentService.java:702-780` (roughly)

This is a pure refactor of existing, already-verified logic — no behavior change. It exists so `EmployeeProfileService` (Task 4/5) can reuse the exact same employee-folder-name + ambiguity-guard logic that `PdfDocumentService` already has, instead of duplicating the SQL query in a second place.

- [ ] **Step 1: Write the failing test**

```java
package com.daf360.rh.sharepoint;

import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeFolderResolverTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks EmployeeFolderResolver resolver;

    @Test
    void normalize_collapsesMultipleSpacesAndTrims() {
        assertThat(resolver.normalize("  Abir   ESSAYEM  ")).isEqualTo("Abir ESSAYEM");
    }

    @Test
    void normalize_returnsNullForBlankOrNullInput() {
        assertThat(resolver.normalize("   ")).isNull();
        assertThat(resolver.normalize(null)).isNull();
    }

    @Test
    void isAmbiguous_trueWhenTwoEmployeesShareSameFullNameInSamePays() {
        when(jdbc.queryForObject(eq(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)"),
                eq(Integer.class), eq(3L), eq("Jean DUPONT")))
                .thenReturn(2);

        assertThat(resolver.isAmbiguous("Jean DUPONT", 3L)).isTrue();
    }

    @Test
    void isAmbiguous_falseWhenOnlyOneMatch() {
        when(jdbc.queryForObject(eq(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)"),
                eq(Integer.class), eq(3L), eq("Jean DUPONT")))
                .thenReturn(1);

        assertThat(resolver.isAmbiguous("Jean DUPONT", 3L)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=EmployeeFolderResolverTest`
Expected: FAIL to compile — `com.daf360.rh.service.sharepoint.EmployeeFolderResolver` does not exist.

- [ ] **Step 3: Implement `EmployeeFolderResolver`**

```java
package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Résout le nom de dossier SharePoint d'un employé ("Prénom NOM" = Users.fullName,
 * nettoyé) et détecte les homonymies qui rendraient ce nom de dossier ambigu.
 *
 * Extrait de PdfDocumentService (génération de documents) pour être réutilisé tel
 * quel par EmployeeProfileService (photo de profil, cf. spec 2026-08-18) — même
 * convention de nommage, même garde-fou, une seule requête à maintenir plutôt que
 * deux copies qui pourraient diverger avec le temps.
 */
@Component
@RequiredArgsConstructor
public class EmployeeFolderResolver {

    private final JdbcTemplate jdbc;

    /** Nettoie un fullName brut ("Abir  ESSAYEM"-style espaces multiples) en nom de
     * dossier utilisable — n'affecte pas la recherche d'un dossier existant, seulement
     * ce qu'on écrit nous-mêmes. Retourne null si fullName est null/vide. */
    public String normalize(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().replaceAll("\\s+", " ");
    }

    /** Garde-fou : si plusieurs employés du même pays partagent exactement le même
     * fullName (comparaison insensible à la casse), le nom de dossier "Prénom NOM" est
     * ambigu — refuser d'y déposer/lire quoi que ce soit plutôt que de risquer de
     * mélanger les documents (ou la photo) de deux employés différents. Scope par
     * pays_id : chaque pays a son propre site SharePoint. */
    public boolean isAmbiguous(String employeeFolder, Long paysId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)",
                Integer.class, paysId, employeeFolder.trim());
        return count != null && count > 1;
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn test -Dtest=EmployeeFolderResolverTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Switch `PdfDocumentService` to use it, deleting the now-duplicate private method**

In `PdfDocumentService.java`, add a constructor-injected field (goes with the other `private final` fields near the top of the class, alongside `graphSharePointService`):

```java
    private final com.daf360.rh.service.sharepoint.EmployeeFolderResolver employeeFolderResolver;
```

Replace this block inside `saveGeneratedDocument(...)`:

```java
        String sharepointUrl = null;
        if (sharepointLocation != null && !sharepointLocation.isBlank()
                && employeeName != null && !employeeName.isBlank()) {
            // Un fullName peut porter des espaces multiples ("Abir  ESSAYEM"-style saisies) —
            // on nettoie ce qu'on ECRIT nous-memes, ca n'affecte pas la recherche d'un dossier
            // existant (aucun de nos employes actuels n'a besoin de matcher un tel double-espace).
            String employeeFolder = employeeName.trim().replaceAll("\\s+", " ");
            if (hasAmbiguousEmployeeFolder(employeeFolder, paysId)) {
                log.warn("Plusieurs employes partagent le nom de dossier SharePoint '{}' — depot SharePoint " +
                        "ignore par securite pour {} (copie locale conservee)", employeeFolder, fileName);
            } else {
                sharepointUrl = graphSharePointService
                        .uploadDocument(sharepointLocation, employeeFolder, fileName, pdfBytes)
                        .orElse(null);
            }
        }
```

with:

```java
        String sharepointUrl = null;
        String employeeFolder = employeeFolderResolver.normalize(employeeName);
        if (sharepointLocation != null && !sharepointLocation.isBlank() && employeeFolder != null) {
            if (employeeFolderResolver.isAmbiguous(employeeFolder, paysId)) {
                log.warn("Plusieurs employes partagent le nom de dossier SharePoint '{}' — depot SharePoint " +
                        "ignore par securite pour {} (copie locale conservee)", employeeFolder, fileName);
            } else {
                sharepointUrl = graphSharePointService
                        .uploadDocument(sharepointLocation, employeeFolder, fileName, pdfBytes)
                        .orElse(null);
            }
        }
```

Keep the `log.warn(...)` call's message text byte-for-byte identical to what's currently in the file — read the live file before editing rather than retyping it, in case its exact accenting differs from what's shown above.

Then **delete** the now-unused private method entirely:

```java
    private boolean hasAmbiguousEmployeeFolder(String employeeFolder, Long paysId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT ep.id) FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.pays_id = ? AND UPPER(LTRIM(RTRIM(u.fullName))) = UPPER(?)",
                Integer.class, paysId, employeeFolder.trim());
        return count != null && count > 1;
    }
```

- [ ] **Step 6: Run the full test suite to confirm nothing broke**

Run: `mvn clean test`
Expected: BUILD SUCCESS — no test in the suite exercised `PdfDocumentService`'s private ambiguity check directly (none existed before this refactor either), so a clean compile + all-green existing suite is the correct bar here, not a new dedicated regression test.

- [ ] **Step 7: Mark task complete** (do NOT commit)

---

## Task 3: `GraphSharePointService.downloadFile()`

**Files:**
- Modify: `src/main/java/com/daf360/rh/service/sharepoint/GraphSharePointService.java`
- Test: `src/test/java/com/daf360/rh/sharepoint/GraphSharePointServiceTest.java`

Note on test scope: this class's existing `uploadDocument()` method has **no** unit test today — its actual Graph HTTP behavior (auth, folder creation, upload) has only ever been verified via live manual testing (see project memory `project-sharepoint-architecture`), because `restClient` is a `private final` field constructed inline (`RestClient.create()`), not constructor-injected, so it can't be swapped for a mock without changing that established class's shape. This plan follows the same precedent for `downloadFile()`: unit-test the one thing that's cleanly testable without touching `restClient` at all (the "not configured" short-circuit), and leave the real Graph-call verification to Task 6's live test.

- [ ] **Step 1: Write the failing test**

```java
package com.daf360.rh.sharepoint;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GraphSharePointServiceTest {

    @Mock AppProperties appProperties;
    @InjectMocks GraphSharePointService service;

    @Test
    void downloadFile_returnsEmptyWhenSharePointNotConfigured() {
        // appProperties is an unstubbed mock — every getMsGraph*() getter defaults to
        // null, so isConfigured() is false without needing to stub anything, and the
        // method must return before ever touching RestClient/the network.
        assertThat(service.downloadFile("Tunisia/01_HR/01_Contracts-Employment/Jean DUPONT/Identity Documents/Photo.jpg"))
                .isEmpty();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=GraphSharePointServiceTest`
Expected: FAIL to compile — `downloadFile` does not exist on `GraphSharePointService`.

- [ ] **Step 3: Implement `downloadFile()`**

Add this public method to `GraphSharePointService.java`, next to `uploadDocument`:

```java
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
            String url = GRAPH_BASE + "/sites/" + siteId + "/drive/root:/" + path + ":/content";
            byte[] content = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(byte[].class);
            return Optional.ofNullable(content);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Échec du téléchargement SharePoint pour {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
```

Update the class-level javadoc (top of file, currently says "pas de suppression, pas de lecture de fichiers existants... juste ce qu'il faut pour PdfDocumentService.saveGeneratedDocument()") to reflect the new second consumer:

```java
/**
 * Client Microsoft Graph minimal — auth (client credentials) + résolution de site +
 * recherche/création de dossier + upload/téléchargement de fichier. Portée
 * volontairement étroite : pas de suppression, pas de gestion multi-site. Deux
 * consommateurs : PdfDocumentService.saveGeneratedDocument() (upload uniquement) et
 * EmployeeProfileService (photo de profil — upload ET téléchargement, pour le cache
 * local auto-réparateur, cf. spec 2026-08-18).
 *
 * Conçu pour ne JAMAIS faire échouer l'appelant : toute erreur (config absente,
 * auth invalide, réseau, permissions, fichier introuvable) est loguée et avalée —
 * l'appelant reçoit simplement Optional.empty() et retombe sur son propre
 * comportement de repli (copie locale déjà enregistrée pour l'upload ; 404 pour le
 * téléchargement).
 */
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn test -Dtest=GraphSharePointServiceTest`
Expected: PASS.

- [ ] **Step 5: Mark task complete** (do NOT commit)

---

## Task 4: `EmployeeProfileService.uploadPhoto` — mirror to SharePoint

**Files:**
- Modify: `src/main/java/com/daf360/rh/domain/EmployeeProfile.java:78-79`
- Modify: `src/main/java/com/daf360/rh/service/EmployeeProfileService.java`
- Test: `src/test/java/com/daf360/rh/profiles/EmployeeProfileServiceTest.java` (new — a Mockito unit test, distinct from the existing Testcontainers `EmployeeProfileIntegrationTest` in the same package)

- [ ] **Step 1: Add the new entity field**

In `EmployeeProfile.java`, right after the existing `photoUrl` field:

```java
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "photo_sharepoint_url", length = 1000)
    private String photoSharepointUrl;
```

- [ ] **Step 2: Write the failing tests**

```java
package com.daf360.rh.profiles;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.profile.EmployeeProfileResponseDto;
import com.daf360.rh.mapper.EmployeeProfileMapper;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.EmployeeProfileService;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeProfileServiceTest {

    @Mock EmployeeProfileRepository   profileRepository;
    @Mock EmployeeProfileMapper       mapper;
    @Mock AuditService                auditService;
    @Mock JdbcTemplate                jdbcTemplate;
    @Mock ObjectMapper                objectMapper;
    @Mock AppProperties               appProperties;
    @Mock com.daf360.rh.security.TenantService tenantService;
    @Mock GradeRepository             gradeRepo;
    @Mock DisciplineRepository        disciplineRepo;
    @Mock NogLevelRepository          nogLevelRepo;
    @Mock HrDepartmentRepository      departmentRepo;
    @Mock BankRepository              bankRepo;
    @Mock NationalityRepository       nationalityRepo;
    @Mock WorkingTimeRegimeRepository regimeRepo;
    @Mock GraphSharePointService      graphSharePointService;
    @Mock EmployeeFolderResolver      employeeFolderResolver;

    @InjectMocks EmployeeProfileService service;

    @TempDir Path tempDir;

    private static final Long PROFILE_ID = 42L;
    private static final Long USER_ID    = 7L;
    private static final Long PAYS_ID    = 3L;
    private static final String LOCATION_SQL_MARKER = "photo_sharepoint_location";
    private static final String FULLNAME_SQL_MARKER = "fullName FROM [dbo].[Users]";

    private EmployeeProfile profile;

    @BeforeEach
    void setUp() {
        when(appProperties.getStoragePath()).thenReturn(tempDir.toString());
        profile = EmployeeProfile.builder().id(PROFILE_ID).userId(USER_ID).paysId(PAYS_ID).build();
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(mapper.toResponseDto(any())).thenReturn(new EmployeeProfileResponseDto());
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    void uploadPhoto_savesLocallyAndSkipsSharePoint_whenPaysHasNoLocationConfigured() {
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(null);

        EmployeeProfileResponseDto result = service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        assertThat(result).isNotNull();
        verify(profileRepository).save(profile);
        verifyNoInteractions(graphSharePointService);
        assertThat(profile.getPhotoSharepointUrl()).isNull();
    }

    @Test
    void uploadPhoto_mirrorsToSharePointWithFixedFilename_whenConfiguredAndUnambiguous() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        when(jdbcTemplate.queryForObject(contains(FULLNAME_SQL_MARKER), eq(String.class), eq(USER_ID)))
                .thenReturn("Jean Dupont");
        when(employeeFolderResolver.normalize("Jean Dupont")).thenReturn("Jean Dupont");
        when(employeeFolderResolver.isAmbiguous("Jean Dupont", PAYS_ID)).thenReturn(false);
        when(graphSharePointService.uploadDocument(eq(template), eq("Jean Dupont"), eq("Photo.jpg"), any()))
                .thenReturn(Optional.of("https://pinigroup.sharepoint.com/.../Photo.jpg"));

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService).uploadDocument(template, "Jean Dupont", "Photo.jpg", new byte[]{1, 2, 3});
        assertThat(profile.getPhotoSharepointUrl()).isEqualTo("https://pinigroup.sharepoint.com/.../Photo.jpg");
    }

    @Test
    void uploadPhoto_skipsSharePoint_whenEmployeeFolderIsAmbiguous() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        when(jdbcTemplate.queryForObject(contains(FULLNAME_SQL_MARKER), eq(String.class), eq(USER_ID)))
                .thenReturn("Jean Dupont");
        when(employeeFolderResolver.normalize("Jean Dupont")).thenReturn("Jean Dupont");
        when(employeeFolderResolver.isAmbiguous("Jean Dupont", PAYS_ID)).thenReturn(true);

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(profileRepository).save(profile);
        verify(graphSharePointService, never()).uploadDocument(any(), any(), any(), any());
        assertThat(profile.getPhotoSharepointUrl()).isNull();
    }

    @Test
    void uploadPhoto_neverThrows_whenSharePointMirroringBlowsUp() {
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenThrow(new RuntimeException("DB connection reset"));

        EmployeeProfileResponseDto result = service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        assertThat(result).isNotNull();
        verify(profileRepository).save(profile);
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `mvn test -Dtest=EmployeeProfileServiceTest`
Expected: FAIL — `EmployeeProfileService` has no `EmployeeFolderResolver`/`GraphSharePointService` constructor dependency yet, `EmployeeProfile` has no `photoSharepointUrl`, and `uploadPhoto` never calls `graphSharePointService`.

- [ ] **Step 4: Implement the SharePoint mirroring in `EmployeeProfileService`**

Add imports near the top of `EmployeeProfileService.java`:

```java
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
```

Add two new fields (near `appProperties`):

```java
    // ── SharePoint (profile photo mirroring, cf. spec 2026-08-18) ─────────────
    private final GraphSharePointService graphSharePointService;
    private final EmployeeFolderResolver employeeFolderResolver;
```

Replace the body of `uploadPhoto` — specifically the `try` block — from:

```java
        try {
            // Build storage path
            String ext = contentType.contains("png") ? ".png"
                       : contentType.contains("webp") ? ".webp" : ".jpg";
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());
            java.nio.file.Files.createDirectories(dir);
            String filename = java.util.UUID.randomUUID() + ext;
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.copy(file.getInputStream(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Store the API path as photo_url (frontend will prefix with hrApiUrl)
            profile.setPhotoUrl("/api/hr/profiles/" + profileId + "/photo");
            profile.setUpdatedAt(java.time.OffsetDateTime.now());
            profileRepository.save(profile);

            auditService.log(actorId(auth), "UPLOAD_PHOTO", "EmployeeProfile", profileId,
                    null, "Photo mise à jour");
            log.info("Photo uploaded for profileId={}", profileId);

            return toResponseDto(profileRepository.findById(profileId).orElseThrow(), auth);

        } catch (java.io.IOException e) {
            log.error("Failed to store photo for profile {}: {}", profileId, e.getMessage());
            throw new AppException(com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Erreur lors du téléversement de la photo.");
        }
```

to:

```java
        try {
            byte[] bytes = file.getBytes();

            // Build storage path
            String ext = contentType.contains("png") ? ".png"
                       : contentType.contains("webp") ? ".webp" : ".jpg";
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());
            java.nio.file.Files.createDirectories(dir);
            String filename = java.util.UUID.randomUUID() + ext;
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.write(target, bytes);

            // Store the API path as photo_url (frontend will prefix with hrApiUrl)
            profile.setPhotoUrl("/api/hr/profiles/" + profileId + "/photo");
            profile.setUpdatedAt(java.time.OffsetDateTime.now());

            // Best-effort mirror to SharePoint — mirrorPhotoToSharePoint() never throws
            // (see its own try/catch), so it can never block the save below, even if
            // SharePoint is completely unreachable.
            mirrorPhotoToSharePoint(profile, ext, bytes);

            profileRepository.save(profile);

            auditService.log(actorId(auth), "UPLOAD_PHOTO", "EmployeeProfile", profileId,
                    null, "Photo mise à jour");
            log.info("Photo uploaded for profileId={}", profileId);

            return toResponseDto(profileRepository.findById(profileId).orElseThrow(), auth);

        } catch (java.io.IOException e) {
            log.error("Failed to store photo for profile {}: {}", profileId, e.getMessage());
            throw new AppException(com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Erreur lors du téléversement de la photo.");
        }
```

(`Files.copy(file.getInputStream(), ...)` became `Files.write(target, bytes)`: the multipart file's input stream can only be read once, and the SharePoint mirror step below needs the same bytes, so they're read once via `file.getBytes()` and reused for both the local write and the SharePoint upload. `REPLACE_EXISTING` was dropped since `target` is always a brand-new UUID filename — it never actually replaced anything.)

Add these new private helpers (near the other private helpers, e.g. right before `findOrThrow`):

```java
    // ── SharePoint mirroring (profile photo) ───────────────────────────────────

    /** Best-effort: upload profile photo bytes to SharePoint under the employee's
     * folder, using a FIXED filename per employee ("Photo.jpg"/.png/.webp) so a
     * re-upload cleanly overwrites the previous SharePoint copy instead of
     * accumulating versions the way local disk currently does. Never throws — any
     * failure (unconfigured, no per-pays location, ambiguous employee name,
     * network/auth) just leaves photoSharepointUrl unset; the local save has
     * already succeeded regardless of what happens in here. */
    private void mirrorPhotoToSharePoint(EmployeeProfile profile, String ext, byte[] bytes) {
        try {
            EmployeeSharepointFolder folder =
                    resolveEmployeeSharepointFolder(profile.getPaysId(), profile.getUserId());
            if (folder == null) return;

            String fixedFileName = "Photo" + ext;
            String sharepointUrl = graphSharePointService
                    .uploadDocument(folder.locationTemplate(), folder.employeeFolder(), fixedFileName, bytes)
                    .orElse(null);
            profile.setPhotoSharepointUrl(sharepointUrl);
        } catch (Exception e) {
            log.warn("Échec du mirroring SharePoint de la photo pour profileId={}: {}",
                    profile.getId(), e.getMessage());
        }
    }

    /** pays.photo_sharepoint_location for this profile's pays, the employee's
     * resolved+normalized folder name, and the ambiguity guard — every step needed
     * before either mirroring an upload or self-healing a serve cache-miss (cf.
     * fetchAndCachePhotoFromSharePoint). Returns null if SharePoint mirroring should
     * be skipped for this profile, for any reason (no location configured for this
     * pays, no usable employee name, or an ambiguous name). */
    private EmployeeSharepointFolder resolveEmployeeSharepointFolder(Long paysId, Long userId) {
        String locationTemplate = jdbcTemplate.queryForObject(
                "SELECT photo_sharepoint_location FROM [dbo].[pays] WHERE id = ?",
                String.class, paysId);
        if (locationTemplate == null || locationTemplate.isBlank()) return null;

        String fullName = jdbcTemplate.queryForObject(
                "SELECT fullName FROM [dbo].[Users] WHERE id = ?", String.class, userId);
        String employeeFolder = employeeFolderResolver.normalize(fullName);
        if (employeeFolder == null) return null;

        if (employeeFolderResolver.isAmbiguous(employeeFolder, paysId)) {
            log.warn("Plusieurs employés partagent le nom de dossier SharePoint '{}' — opération " +
                    "photo ignorée par sécurité", employeeFolder);
            return null;
        }

        return new EmployeeSharepointFolder(locationTemplate, employeeFolder);
    }

    private record EmployeeSharepointFolder(String locationTemplate, String employeeFolder) {}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn test -Dtest=EmployeeProfileServiceTest`
Expected: PASS, 4 tests green.

- [ ] **Step 6: Run the full suite**

Run: `mvn clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Mark task complete** (do NOT commit)

---

## Task 5: `EmployeeProfileService.servePhoto` — self-healing cache

**Files:**
- Modify: `src/main/java/com/daf360/rh/service/EmployeeProfileService.java`
- Test: `src/test/java/com/daf360/rh/profiles/EmployeeProfileServiceTest.java` (same file as Task 4 — add these tests to the same class)

- [ ] **Step 1: Write the failing tests**

Add to `EmployeeProfileServiceTest`:

```java
    @Test
    void servePhoto_returnsLocalFileWithoutTouchingSharePoint_whenCachePresent() throws Exception {
        java.nio.file.Path dir = tempDir.resolve("profiles").resolve(PROFILE_ID.toString());
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("existing.jpg"), new byte[]{9, 9, 9});

        byte[] result = service.servePhoto(PROFILE_ID);

        assertThat(result).isEqualTo(new byte[]{9, 9, 9});
        verifyNoInteractions(graphSharePointService);
    }

    @Test
    void servePhoto_selfHealsFromSharePoint_whenLocalCacheEmpty() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        when(jdbcTemplate.queryForObject(contains(FULLNAME_SQL_MARKER), eq(String.class), eq(USER_ID)))
                .thenReturn("Jean Dupont");
        when(employeeFolderResolver.normalize("Jean Dupont")).thenReturn("Jean Dupont");
        when(employeeFolderResolver.isAmbiguous("Jean Dupont", PAYS_ID)).thenReturn(false);
        String basePath = "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Identity Documents";
        when(graphSharePointService.downloadFile(basePath + "/Photo.jpg"))
                .thenReturn(Optional.of(new byte[]{5, 5, 5}));

        byte[] result = service.servePhoto(PROFILE_ID);

        assertThat(result).isEqualTo(new byte[]{5, 5, 5});
        java.nio.file.Path dir = tempDir.resolve("profiles").resolve(PROFILE_ID.toString());
        try (var files = java.nio.file.Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1); // self-healed: now cached locally for next time
        }
    }

    @Test
    void servePhoto_returnsNull_whenLocalCacheEmptyAndSharePointHasNothing() {
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(null);

        byte[] result = service.servePhoto(PROFILE_ID);

        assertThat(result).isNull();
    }
```

Add `java.io.IOException` to the test method signature only where used (`servePhoto_returnsLocalFileWithoutTouchingSharePoint_whenCachePresent` already declares `throws Exception` above, which covers it).

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn test -Dtest=EmployeeProfileServiceTest`
Expected: FAIL — `servePhoto` doesn't call `graphSharePointService` at all yet, so the self-heal test gets `null` back instead of the SharePoint bytes, and the local-cache-miss test currently returns `null` for the wrong reason (harmlessly passes) but the self-heal test genuinely fails.

- [ ] **Step 3: Implement the self-healing fallback**

Replace the entire `servePhoto` method body:

```java
    @Transactional(readOnly = true)
    public byte[] servePhoto(Long profileId) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());

            byte[] cached = readMostRecentLocalFile(dir);
            if (cached != null) return cached;

            // Cache miss (no local file at all) — self-heal from SharePoint if
            // configured for this employee, then cache the result for next time.
            return fetchAndCachePhotoFromSharePoint(profileId, dir);

        } catch (Exception e) {
            // Exception, not IOException: see the class-level note above.
            log.warn("Cannot serve photo for profile {}: {}: {}",
                    profileId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private byte[] readMostRecentLocalFile(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(dir)) return null;

        java.nio.file.Path latest;
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(dir)) {
            latest = entries
                    .filter(java.nio.file.Files::isRegularFile)
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p).toMillis(); }
                        catch (java.io.IOException e) { return 0L; }
                    }))
                    .orElse(null);
        }
        return latest != null ? java.nio.file.Files.readAllBytes(latest) : null;
    }

    /** Auto-réparation du cache local : ne s'exécute que quand aucun fichier local
     * n'existe (nouveau serveur, cache vidé, etc.) — jamais sur le chemin rapide
     * habituel (readMostRecentLocalFile ci-dessus retourne alors directement).
     * Reconstruit le même chemin déterministe que celui utilisé à l'upload
     * (mirrorPhotoToSharePoint) ; l'extension n'étant pas persistée séparément, les
     * trois extensions supportées sont essayées dans l'ordre. Toute exception ici
     * remonte au catch-all de servePhoto() ci-dessus — pas besoin d'un try/catch
     * séparé, contrairement à mirrorPhotoToSharePoint qui est appelé depuis
     * uploadPhoto (dont le catch ne couvre que IOException). */
    private byte[] fetchAndCachePhotoFromSharePoint(Long profileId, java.nio.file.Path dir) {
        EmployeeProfile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null) return null;

        EmployeeSharepointFolder folder =
                resolveEmployeeSharepointFolder(profile.getPaysId(), profile.getUserId());
        if (folder == null) return null;

        String basePath = folder.locationTemplate().replace("{employeeFolder}", folder.employeeFolder());
        for (String candidate : java.util.List.of("Photo.jpg", "Photo.png", "Photo.webp")) {
            java.util.Optional<byte[]> content =
                    graphSharePointService.downloadFile(basePath + "/" + candidate);
            if (content.isPresent()) {
                cacheLocally(dir, candidate, content.get());
                return content.get();
            }
        }
        return null;
    }

    private void cacheLocally(java.nio.file.Path dir, String sharePointFileName, byte[] content) {
        try {
            java.nio.file.Files.createDirectories(dir);
            String ext = sharePointFileName.substring(sharePointFileName.lastIndexOf('.'));
            java.nio.file.Path target = dir.resolve(java.util.UUID.randomUUID() + ext);
            java.nio.file.Files.write(target, content);
        } catch (java.io.IOException e) {
            // Best-effort caching only — the caller already has the bytes to serve
            // regardless of whether this local write succeeds.
            log.warn("Could not cache SharePoint photo locally at {}: {}", dir, e.getMessage());
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=EmployeeProfileServiceTest`
Expected: PASS, all 7 tests in the class green (4 from Task 4 + 3 from this task).

- [ ] **Step 5: Run the full suite**

Run: `mvn clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Mark task complete** (do NOT commit)

---

## Task 6: Manual/live verification against real SharePoint

**Files:** none (no code changes — this is a manual verification pass, matching how the original document-generation SharePoint integration was verified)

This task is done directly against the real, live SharePoint site (same tenant/credentials already configured for HR document generation) and the real local `DAF360_HR` database — not automated, per the design spec's testing plan and the precedent set by the original SharePoint integration work.

- [ ] **Step 1: Pick one real, unambiguous test employee**

Query the local DB for a Tunisia employee whose `fullName` is not shared by anyone else in the same pays (reuse the same check `EmployeeFolderResolver.isAmbiguous` encodes):

```sql
SELECT ep.id AS profile_id, u.fullName
FROM employee_profiles ep JOIN [dbo].[Users] u ON u.id = ep.user_id
JOIN pays p ON p.id = ep.pays_id
WHERE p.iso_code = 'TN'
GROUP BY ep.id, u.fullName
HAVING COUNT(*) OVER (PARTITION BY UPPER(LTRIM(RTRIM(u.fullName)))) = 1;
```

(Or reuse a known-good name from the original SharePoint work's audit, e.g. Abdellatif SASSI, if still present and unambiguous.)

- [ ] **Step 2: Upload a photo for that employee through the real API**

`POST /api/hr/profiles/{profileId}/photo` with a small real JPEG, using valid HR credentials/permissions. Confirm the HTTP response is 200 with a normal profile DTO (no SharePoint-specific fields leak into the response — none were added to the DTO).

- [ ] **Step 3: Confirm it landed in SharePoint**

List the real folder at `Tunisia/01_HR/01_Contracts-Employment/{employee folder}/Identity Documents` via Graph (same approach used throughout the original SharePoint work) and confirm a `Photo.jpg` (or `.png`/`.webp`, matching what was uploaded) is present with recent timestamp.

- [ ] **Step 4: Confirm the "Identity Documents" folder name**

While there, confirm whether "Identity Documents" is in fact the real, already-existing subfolder name, or whether this step just auto-created it fresh (mkdir-p). If the user has since told you the real name differs, update the `V77` migration's seeded value (Task 1) accordingly before considering this shipped.

- [ ] **Step 5: Verify the self-healing cache**

Delete the locally-cached file only (`{storagePath}/profiles/{profileId}/`, leave SharePoint untouched), then `GET /api/hr/profiles/{profileId}/photo` again. Confirm: (a) the response still returns the correct photo bytes, (b) a new file has reappeared in the local cache directory afterward (proving the self-heal-and-recache path actually ran, not just a lucky coincidence).

- [ ] **Step 6: Verify a re-upload overwrites, not duplicates, the SharePoint copy**

Upload a second, visibly-different photo for the same employee. Re-list the SharePoint folder and confirm there is still exactly **one** `Photo.*` file (the fixed-filename overwrite behavior working as designed), not two.

- [ ] **Step 7: Report findings to the user**

Summarize what was verified (and, if applicable, what was corrected — e.g. the real subfolder name if different from "Identity Documents") before considering this feature done.

- [ ] **Step 8: Mark task complete** (do NOT commit)
