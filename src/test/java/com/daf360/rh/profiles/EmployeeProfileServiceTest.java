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

    private EmployeeProfile profile;

    @BeforeEach
    void setUp() {
        when(appProperties.getStoragePath()).thenReturn(tempDir.toString());
        profile = EmployeeProfile.builder().id(PROFILE_ID).userId(USER_ID).paysId(PAYS_ID).build();
        // lenient: servePhoto's tests don't all exercise findById/toResponseDto (e.g. the
        // cache-hit path returns before either is reached) — without lenient(), Mockito's
        // strict-stubbing check flags the unused shared stub as an error even though the
        // test itself passes.
        lenient().when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient().when(mapper.toResponseDto(any())).thenReturn(new EmployeeProfileResponseDto());
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
        // The name lookup and the homonym guard moved into EmployeeFolderResolver.resolve(),
        // shared with the document mirror — so one stub now stands in for the pair.
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(template, "Jean Dupont"));
        when(graphSharePointService.uploadDocument(eq(template), eq("Jean Dupont"), eq("Photo.jpg"), any()))
                .thenReturn(Optional.of("https://pinigroup.sharepoint.com/.../Photo.jpg"));

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService).uploadDocument(template, "Jean Dupont", "Photo.jpg", new byte[]{1, 2, 3});
        assertThat(profile.getPhotoSharepointUrl()).isEqualTo("https://pinigroup.sharepoint.com/.../Photo.jpg");
    }

    @Test
    void uploadPhoto_deletesStaleSiblingExtensions_whenReUploadedInDifferentFormat() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        String basePath = "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        // The name lookup and the homonym guard moved into EmployeeFolderResolver.resolve(),
        // shared with the document mirror — so one stub now stands in for the pair.
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(template, "Jean Dupont"));
        when(graphSharePointService.uploadDocument(eq(template), eq("Jean Dupont"), anyString(), any()))
                .thenReturn(Optional.of("https://pinigroup.sharepoint.com/.../Photo"));

        // First upload: .jpg — must clean up the *other* two possible extensions,
        // never its own (nothing to clean up for a brand-new employee folder, but
        // deleteFileIfExists is itself a silent no-op when the file is absent).
        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService).uploadDocument(template, "Jean Dupont", "Photo.jpg", new byte[]{1, 2, 3});
        verify(graphSharePointService).deleteFileIfExists(basePath + "/Photo.png");
        verify(graphSharePointService).deleteFileIfExists(basePath + "/Photo.webp");
        verify(graphSharePointService, never()).deleteFileIfExists(basePath + "/Photo.jpg");

        // Second upload for the SAME profile, now in .png — must delete the stale
        // .jpg left behind by the first upload (plus .webp again), and must NOT
        // issue a second delete for .png (it's the format being written this time).
        // Note the cumulative counts below: Photo.png was already deleted once
        // during the FIRST upload above (ext was .jpg then, so .png was a "stale
        // sibling" at that point) — this call must not add a second one.
        MockMultipartFile pngFile = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{4, 5, 6});
        service.uploadPhoto(PROFILE_ID, pngFile, null);

        verify(graphSharePointService).uploadDocument(template, "Jean Dupont", "Photo.png", new byte[]{4, 5, 6});
        verify(graphSharePointService, times(1)).deleteFileIfExists(basePath + "/Photo.jpg");
        verify(graphSharePointService, times(2)).deleteFileIfExists(basePath + "/Photo.webp");
        verify(graphSharePointService, times(1)).deleteFileIfExists(basePath + "/Photo.png");
    }

    @Test
    void uploadPhoto_skipsSharePoint_whenEmployeeFolderIsAmbiguous() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        // Homonyms make the folder ambiguous, and resolve() answers that by refusing to
        // name a folder at all rather than risking two people's files in one place.
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID)).thenReturn(null);

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
    void servePhoto_selfHealsFromSharePoint_whenLocalCacheEmpty() throws Exception {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        // The name lookup and the homonym guard moved into EmployeeFolderResolver.resolve(),
        // shared with the document mirror — so one stub now stands in for the pair.
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(template, "Jean Dupont"));
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
    void servePhoto_selfHealsFromSharePoint_whenLocalDirectoryExistsButIsEmpty() throws Exception {
        // Distinct from servePhoto_selfHealsFromSharePoint_whenLocalCacheEmpty above: that
        // test never creates the profile's directory at all, so it only exercises
        // readMostRecentLocalFile's `!Files.isDirectory(dir)` early-return path. Here the
        // directory exists (e.g. left behind by a prior upload attempt) but holds no files,
        // so Files.list(dir) actually runs and falls through its stream empty — the exact
        // code path this class's javadoc documents two prior bugs in (a leaked Files.list()
        // handle, and an UncheckedIOException escaping too narrow a catch).
        java.nio.file.Path dir = tempDir.resolve("profiles").resolve(PROFILE_ID.toString());
        java.nio.file.Files.createDirectories(dir);

        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        // The name lookup and the homonym guard moved into EmployeeFolderResolver.resolve(),
        // shared with the document mirror — so one stub now stands in for the pair.
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(template, "Jean Dupont"));
        String basePath = "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Identity Documents";
        when(graphSharePointService.downloadFile(basePath + "/Photo.jpg"))
                .thenReturn(Optional.of(new byte[]{5, 5, 5}));

        byte[] result = service.servePhoto(PROFILE_ID);

        assertThat(result).isEqualTo(new byte[]{5, 5, 5});
        try (var files = java.nio.file.Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1); // self-healed: now cached locally for next time
        }
    }

    // ── Photos HR placed by hand, under names we cannot guess ─────────────────

    /**
     * The profile-32 case: the portrait is in SharePoint but not under one of the three
     * names our own mirror writes, so probing those alone 404'd with the file right there.
     */
    @Test
    void servePhoto_fallsBackToWhateverImageIsInTheFolder_whenNoFixedNameMatches() {
        String basePath = givenSharePointFolderConfigured();
        when(graphSharePointService.downloadFile(anyString())).thenReturn(Optional.empty());
        when(graphSharePointService.listFiles(basePath)).thenReturn(java.util.List.of(
                new GraphSharePointService.RemoteFile("IMG_2381.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(basePath + "/IMG_2381.jpg"))
                .thenReturn(Optional.of(new byte[]{6, 6, 6}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{6, 6, 6});
    }

    /**
     * "Identity Documents" also holds ID/passport scans of the same person. Serving one as
     * an avatar would be wrong and a small privacy leak, so an identity-looking name is
     * skipped and the profile falls back to initials.
     */
    @Test
    void servePhoto_refusesIdentityScans_ratherThanShowingThemAsAnAvatar() {
        String basePath = givenSharePointFolderConfigured();
        when(graphSharePointService.downloadFile(anyString())).thenReturn(Optional.empty());
        when(graphSharePointService.listFiles(basePath)).thenReturn(java.util.List.of(
                new GraphSharePointService.RemoteFile("CIN recto.jpg", "2026-08-02T10:00:00Z"),
                new GraphSharePointService.RemoteFile("passeport.png", "2026-08-01T10:00:00Z")));

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
        verify(graphSharePointService, never()).downloadFile(basePath + "/CIN recto.jpg");
    }

    /** An explicit "photo" in the name wins even when the name also mentions a document. */
    @Test
    void servePhoto_prefersAnExplicitlyNamedPhotoOverTheExclusionList() {
        String basePath = givenSharePointFolderConfigured();
        when(graphSharePointService.downloadFile(anyString())).thenReturn(Optional.empty());
        when(graphSharePointService.listFiles(basePath)).thenReturn(java.util.List.of(
                new GraphSharePointService.RemoteFile("photo carte.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(basePath + "/photo carte.jpg"))
                .thenReturn(Optional.of(new byte[]{8, 8}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{8, 8});
    }

    /** Non-images in the folder (a PDF contract) are never offered to an <img> tag. */
    @Test
    void servePhoto_ignoresNonImageFiles() {
        String basePath = givenSharePointFolderConfigured();
        when(graphSharePointService.downloadFile(anyString())).thenReturn(Optional.empty());
        when(graphSharePointService.listFiles(basePath)).thenReturn(java.util.List.of(
                new GraphSharePointService.RemoteFile("attestation.pdf", "2026-08-01T10:00:00Z")));

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
    }

    /** Shared setup for the fallback tests: TN location + resolvable folder. */
    private String givenSharePointFolderConfigured() {
        String template = "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(template);
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(template, "Jean Dupont"));
        return "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Identity Documents";
    }

    @Test
    void servePhoto_returnsNull_whenLocalCacheEmptyAndSharePointHasNothing() {
        when(jdbcTemplate.queryForObject(contains(LOCATION_SQL_MARKER), eq(String.class), eq(PAYS_ID)))
                .thenReturn(null);

        byte[] result = service.servePhoto(PROFILE_ID);

        assertThat(result).isNull();
    }
}
