package com.daf360.rh.profiles;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.profile.EmployeeProfileResponseDto;
import com.daf360.rh.mapper.EmployeeProfileMapper;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.EmployeeProfileService;
import com.daf360.rh.service.photo.ProfilePhotoCache;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.ResolutionSource;
import com.daf360.rh.service.sharepoint.SharePointResolver;
import com.daf360.rh.service.sharepoint.SharePointStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The profile photo, end to end through the service.
 *
 * <p>Rewritten when the photo moved onto {@code SharePointResolver}: this class used to stub
 * the {@code pays.photo_sharepoint_location} query and {@code EmployeeFolderResolver} directly,
 * because the folder rules lived here in a private copy. They no longer do, so the tests now
 * describe orchestration — resolve, pick a portrait, cache, revalidate — and the local cache
 * has its own tests in {@link com.daf360.rh.photo.ProfilePhotoCacheTest}.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeProfileServiceTest {

    @Mock EmployeeProfileRepository   profileRepository;
    @Mock EmployeeProfileMapper       mapper;
    @Mock AuditService                auditService;
    @Mock JdbcTemplate                jdbcTemplate;
    @Mock ObjectMapper                objectMapper;
    @Mock com.daf360.rh.security.TenantService tenantService;
    @Mock GraphSharePointService      graphSharePointService;
    @Mock SharePointResolver          sharePointResolver;
    @Mock ProfilePhotoCache           photoCache;
    @Mock GradeRepository             gradeRepo;
    @Mock DisciplineRepository        disciplineRepo;
    @Mock NogLevelRepository          nogLevelRepo;
    @Mock HrDepartmentRepository      departmentRepo;
    @Mock BankRepository              bankRepo;
    @Mock NationalityRepository       nationalityRepo;
    @Mock WorkingTimeRegimeRepository regimeRepo;

    @InjectMocks EmployeeProfileService service;

    private static final Long PROFILE_ID = 42L;
    private static final Long USER_ID    = 7L;
    private static final Long PAYS_ID    = 3L;

    private static final String BASE_PATH =
            "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Identity Documents";

    private EmployeeProfile profile;

    @BeforeEach
    void setUp() {
        profile = EmployeeProfile.builder().id(PROFILE_ID).userId(USER_ID).paysId(PAYS_ID).build();
        lenient().when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient().when(mapper.toResponseDto(any())).thenReturn(new EmployeeProfileResponseDto());
        // Default: nothing cached, so tests opt in to a cache hit rather than out of one.
        lenient().when(photoCache.read(anyLong())).thenReturn(Optional.empty());
        lenient().when(photoCache.write(anyLong(), anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3));
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private SharePointResolver.ResolvedLocation found() {
        return new SharePointResolver.ResolvedLocation(SharePointStatus.FOUND, BASE_PATH, BASE_PATH,
                "Jean Dupont", ResolutionSource.DISCOVERED, null);
    }

    private SharePointResolver.ResolvedLocation missing() {
        return new SharePointResolver.ResolvedLocation(SharePointStatus.FOLDER_MISSING, null, null,
                null, null, "dossier introuvable: " + BASE_PATH);
    }

    private void givenFolderResolves() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO)).thenReturn(found());
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * photo_url must carry a version token. Without one the URL is a constant, so the seven-day
     * cache the endpoint advertises can never be invalidated and a replaced photo stays
     * invisible for a week.
     */
    @Test
    void uploadPhoto_writesThroughTheCacheAndVersionsThePhotoUrl() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO)).thenReturn(missing());

        EmployeeProfileResponseDto result = service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        assertThat(result).isNotNull();
        verify(photoCache).write(eq(PROFILE_ID), eq("upload.jpg"), isNull(), eq(new byte[]{1, 2, 3}));
        verify(profileRepository).save(profile);
        assertThat(profile.getPhotoUrl()).startsWith("/api/hr/profiles/42/photo?v=");
    }

    /** Nothing to mirror into when the location does not resolve — and no exception either. */
    @Test
    void uploadPhoto_skipsSharePoint_whenTheLocationDoesNotResolve() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO)).thenReturn(missing());

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService, never()).uploadToFolder(anyString(), anyString(), any());
    }

    /**
     * The ORIGINAL bytes go to SharePoint, not the shrunk copy: the cache exists to make
     * avatars cheap to serve, not to degrade the archive HR keeps.
     */
    @Test
    void uploadPhoto_mirrorsTheOriginalBytesUnderTheFixedName() {
        givenFolderResolves();

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService).uploadToFolder(BASE_PATH, "Photo.jpg", new byte[]{1, 2, 3});
    }

    /**
     * A re-upload in a different format must remove the previous one, or both coexist forever
     * and the portrait picker has two candidates for the same person.
     */
    @Test
    void uploadPhoto_deletesStaleSiblingExtensions_whenReUploadedInDifferentFormat() {
        givenFolderResolves();

        service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        verify(graphSharePointService).deleteFileIfExists(BASE_PATH + "/Photo.png");
        verify(graphSharePointService).deleteFileIfExists(BASE_PATH + "/Photo.webp");
        verify(graphSharePointService, never()).deleteFileIfExists(BASE_PATH + "/Photo.jpg");

        MockMultipartFile png =
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{4, 5, 6});
        service.uploadPhoto(PROFILE_ID, png, null);

        verify(graphSharePointService).uploadToFolder(BASE_PATH, "Photo.png", new byte[]{4, 5, 6});
        verify(graphSharePointService, times(1)).deleteFileIfExists(BASE_PATH + "/Photo.jpg");
        verify(graphSharePointService, times(2)).deleteFileIfExists(BASE_PATH + "/Photo.webp");
        verify(graphSharePointService, times(1)).deleteFileIfExists(BASE_PATH + "/Photo.png");
    }

    /** The local save must survive any SharePoint failure — it is the copy that gets served. */
    @Test
    void uploadPhoto_neverThrows_whenSharePointMirroringBlowsUp() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO))
                .thenThrow(new RuntimeException("Graph unreachable"));

        EmployeeProfileResponseDto result = service.uploadPhoto(PROFILE_ID, jpegFile(), null);

        assertThat(result).isNotNull();
        verify(profileRepository).save(profile);
    }

    // ── Serve: cache ──────────────────────────────────────────────────────────

    @Test
    void servePhoto_servesTheCacheWithoutTouchingSharePoint_whenStillFresh() {
        when(photoCache.read(PROFILE_ID)).thenReturn(Optional.of(new byte[]{9, 9, 9}));
        when(photoCache.needsRevalidation(PROFILE_ID)).thenReturn(false);

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{9, 9, 9});
        verifyNoInteractions(graphSharePointService, sharePointResolver);
    }

    /**
     * The defect this fixes: the cached file was trusted forever, so replacing a photo in
     * SharePoint had no observable effect. Ever.
     */
    @Test
    void servePhoto_refreshesTheCache_whenSharePointHasANewerCopy() {
        when(photoCache.read(PROFILE_ID)).thenReturn(Optional.of(new byte[]{9, 9, 9}));
        when(photoCache.needsRevalidation(PROFILE_ID)).thenReturn(true);
        when(photoCache.remoteIsNewer(eq(PROFILE_ID), anyString())).thenReturn(true);
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("Photo.jpg", "2026-08-25T10:00:00Z")));
        when(graphSharePointService.downloadFile(BASE_PATH + "/Photo.jpg"))
                .thenReturn(Optional.of(new byte[]{7, 7, 7}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{7, 7, 7});
        // The URL must change too, or browsers keep the old image for the full week.
        verify(jdbcTemplate).update(anyString(), anyString(), eq(PROFILE_ID));
    }

    @Test
    void servePhoto_keepsTheCache_whenSharePointHasNothingNewer() {
        when(photoCache.read(PROFILE_ID)).thenReturn(Optional.of(new byte[]{9, 9, 9}));
        when(photoCache.needsRevalidation(PROFILE_ID)).thenReturn(true);
        when(photoCache.remoteIsNewer(eq(PROFILE_ID), anyString())).thenReturn(false);
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("Photo.jpg", "2026-08-01T10:00:00Z")));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{9, 9, 9});
        verify(graphSharePointService, never()).downloadFile(anyString());
        verify(photoCache, never()).write(anyLong(), anyString(), any(), any());
    }

    /** A revalidation that could not complete must still be recorded, or an unreachable folder
     *  is re-probed on every single render. */
    @Test
    void servePhoto_marksTheCheckDone_evenWhenRevalidationFails() {
        when(photoCache.read(PROFILE_ID)).thenReturn(Optional.of(new byte[]{9, 9, 9}));
        when(photoCache.needsRevalidation(PROFILE_ID)).thenReturn(true);
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO)).thenReturn(missing());

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{9, 9, 9});
        verify(photoCache).markChecked(PROFILE_ID);
    }

    // ── Serve: discovery ──────────────────────────────────────────────────────

    @Test
    void servePhoto_discoversAndCaches_whenNothingIsCachedYet() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("Photo.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(BASE_PATH + "/Photo.jpg"))
                .thenReturn(Optional.of(new byte[]{5, 5, 5}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{5, 5, 5});
        verify(photoCache).write(PROFILE_ID, "Photo.jpg", "2026-08-01T10:00:00Z", new byte[]{5, 5, 5});
    }

    /**
     * A miss must be remembered. This is the cost that made a directory page of photo-less
     * employees a Graph throttling risk: roughly five calls per employee, repeated on every
     * render, because nothing recorded that the answer was already known to be "no".
     */
    @Test
    void servePhoto_recordsAMiss_soItIsNotRetriedOnEveryRender() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO)).thenReturn(missing());

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
        verify(photoCache).markChecked(PROFILE_ID);
        verify(graphSharePointService, never()).listFiles(anyString());
    }

    /**
     * The profile-32 case: the portrait was in SharePoint under a name our own mirror never
     * writes, so probing only the fixed names 404'd with the file sitting right there.
     */
    @Test
    void servePhoto_acceptsAPortraitHrNamedItself() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("Abdellatif_SASSI.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(BASE_PATH + "/Abdellatif_SASSI.jpg"))
                .thenReturn(Optional.of(new byte[]{6, 6, 6}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{6, 6, 6});
    }

    /** Our own mirror's fixed name wins over an arbitrary one when both are present. */
    @Test
    void servePhoto_prefersOurOwnMirrorOverAnArbitraryImage() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("IMG_2381.jpg", "2026-08-09T10:00:00Z"),
                new GraphSharePointService.RemoteFile("Photo.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(BASE_PATH + "/Photo.jpg"))
                .thenReturn(Optional.of(new byte[]{1}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{1});
        verify(graphSharePointService, never()).downloadFile(BASE_PATH + "/IMG_2381.jpg");
    }

    /**
     * "Identity Documents" also holds ID and passport scans of the same person. Serving one as
     * an avatar would be wrong and a small privacy leak, so an identity-looking name is skipped
     * and the profile falls back to initials.
     */
    @Test
    void servePhoto_refusesIdentityScans_ratherThanShowingThemAsAnAvatar() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("CIN recto.jpg", "2026-08-02T10:00:00Z"),
                new GraphSharePointService.RemoteFile("passeport.png", "2026-08-01T10:00:00Z")));

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
        verify(graphSharePointService, never()).downloadFile(anyString());
    }

    /** An explicit "photo" in the name wins even when the name also mentions a document. */
    @Test
    void servePhoto_prefersAnExplicitlyNamedPhotoOverTheExclusionList() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("photo carte.jpg", "2026-08-01T10:00:00Z")));
        when(graphSharePointService.downloadFile(BASE_PATH + "/photo carte.jpg"))
                .thenReturn(Optional.of(new byte[]{8, 8}));

        assertThat(service.servePhoto(PROFILE_ID)).isEqualTo(new byte[]{8, 8});
    }

    /** Non-images in the folder (a PDF attestation) are never offered to an img tag. */
    @Test
    void servePhoto_ignoresNonImageFiles() {
        givenFolderResolves();
        when(graphSharePointService.listFiles(BASE_PATH)).thenReturn(List.of(
                new GraphSharePointService.RemoteFile("attestation.pdf", "2026-08-01T10:00:00Z")));

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
    }

    @Test
    void servePhoto_returnsNullRatherThanThrowing_whenAnythingBlowsUp() {
        when(sharePointResolver.resolve(PROFILE_ID, DocKind.PHOTO))
                .thenThrow(new RuntimeException("Graph unreachable"));

        assertThat(service.servePhoto(PROFILE_ID)).isNull();
    }
}
