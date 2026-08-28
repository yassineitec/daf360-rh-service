package com.daf360.rh.documents;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.mapper.EmployeeDocumentMapper;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.EmployeeDocumentService;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * The SharePoint mirror on uploaded employee documents.
 *
 * <p>The invariant these tests defend is the one that makes the feature safe to switch on:
 * the local save and the DB row are the record, the SharePoint copy is best-effort, and
 * NOTHING about a failing or unconfigured Graph can make an upload fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeDocumentSharePointTest {

    @Mock EmployeeDocumentRepository documentRepository;
    @Mock EmployeeProfileRepository  profileRepository;
    @Mock EmployeeDocumentMapper     mapper;
    @Mock AuditService               auditService;
    @Mock JdbcTemplate              jdbc;
    @Mock GraphSharePointService     graphSharePointService;
    @Mock EmployeeFolderResolver     employeeFolderResolver;
    // V87: the type vocabulary and the path model both moved to the database. Stubbed in
    // setUp so these tests still exercise the DocumentFolderMapping fallback they were
    // written for — an empty templateForCode is exactly what a server without V87 returns.
    @Mock com.daf360.rh.service.document.DocumentTypeService documentTypeService;
    @Mock com.daf360.rh.service.sharepoint.SharePointLocationService locationService;

    @TempDir Path tempDir;

    private static final Long PROFILE_ID = 42L;
    private static final Long USER_ID    = 7L;
    private static final Long PAYS_ID    = 3L;
    private static final Long DOC_ID     = 99L;

    /** The Tunisian employee-folder root, as V81 seeds it, minus the photo leaf. */
    private static final String BASE =
            "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}";

    private EmployeeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeDocumentService(documentRepository, profileRepository, mapper,
                auditService, jdbc, graphSharePointService, employeeFolderResolver,
                documentTypeService, locationService);

        // V87 moved both the type vocabulary and the document paths into the database. These
        // tests describe the DocumentFolderMapping behaviour, which is what a server WITHOUT
        // V87 still gets — so the location lookup is stubbed empty on purpose, exercising the
        // fallback rather than bypassing it. lenient() because not every test uploads.
        lenient().when(locationService.templateForCode(any(), any())).thenReturn(Optional.empty());
        lenient().when(documentTypeService.validate(any(), anyString()))
                .thenAnswer(inv -> Optional.of(inv.getArgument(1, String.class).toUpperCase()));
        // storagePath is an @Value field, not a constructor arg.
        ReflectionTestUtils.setField(service, "storagePath", tempDir.toString());

        EmployeeProfile profile = EmployeeProfile.builder()
                .id(PROFILE_ID).userId(USER_ID).paysId(PAYS_ID).build();
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(mapper.toDto(any())).thenReturn(new DocumentUploadResponseDto());
        // save() assigns the id in production; the mirror path needs one to build the name.
        when(documentRepository.save(any())).thenAnswer(inv -> {
            EmployeeDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(DOC_ID);
            return d;
        });
    }

    private void givenFolderResolves() {
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID))
                .thenReturn(new EmployeeFolderResolver.EmployeeFolder(BASE, "Jean Dupont"));
    }

    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", new byte[]{1, 2, 3});
    }

    // ── Where the copy goes ───────────────────────────────────────────────────

    /**
     * A signed contract belongs under the employee's contracts folder, and the remote name
     * carries the document id so two uploads of "contrat.pdf" cannot overwrite each other
     * (Graph's PUT replaces by default).
     */
    @Test
    void upload_mirrorsContractIntoTheContractsSubfolder() throws IOException {
        givenFolderResolves();

        service.upload(PROFILE_ID, pdf("contrat.pdf"), "CONTRACT_SIGNED", null, null, null);

        verify(graphSharePointService).uploadDocument(
                BASE + "/Employment Contracts & Amendments",
                "Jean Dupont",
                DOC_ID + "_contrat.pdf",
                new byte[]{1, 2, 3});
    }

    /** An ID scan lands beside the profile photo, which already mirrors to this folder. */
    @Test
    void upload_mirrorsIdentityDocumentsBesideThePhoto() throws IOException {
        givenFolderResolves();

        service.upload(PROFILE_ID, pdf("cin.pdf"), "ID_CARD", null, null, null);

        verify(graphSharePointService).uploadDocument(
                eq(BASE + "/Identity Documents"), eq("Jean Dupont"), anyString(), any());
    }

    /** Types with no home in HR's tree go to our own folder, not into an HR-curated one. */
    @Test
    void upload_sendsUnmappedTypesToTheDefaultFolder() throws IOException {
        givenFolderResolves();

        service.upload(PROFILE_ID, pdf("cv.pdf"), "CV", null, null, null);

        verify(graphSharePointService).uploadDocument(
                eq(BASE + "/Administrative Documents"), eq("Jean Dupont"), anyString(), any());
    }

    /** A '/' in the original name must not retarget the upload into another folder. */
    @Test
    void upload_sanitisesTheRemoteFileName() throws IOException {
        givenFolderResolves();

        service.upload(PROFILE_ID, pdf("../../secret/passwd.pdf"), "OTHER", null, null, null);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(graphSharePointService).uploadDocument(anyString(), anyString(), name.capture(), any());
        assertThat(name.getValue()).doesNotContain("/").startsWith(DOC_ID + "_");
    }

    // ── The invariant: the upload never depends on SharePoint ─────────────────

    /** The row is LOCAL and the file is on disk whether or not the mirror ran. */
    @Test
    void upload_savesLocallyAndKeepsProviderLocal_evenWhenMirrored() throws IOException {
        givenFolderResolves();

        service.upload(PROFILE_ID, pdf("contrat.pdf"), "CONTRACT", null, null, null);

        ArgumentCaptor<EmployeeDocument> saved = ArgumentCaptor.forClass(EmployeeDocument.class);
        verify(documentRepository).save(saved.capture());
        assertThat(saved.getValue().getStorageProvider()).isEqualTo("LOCAL");
        assertThat(Path.of(saved.getValue().getFileUrl())).exists().hasBinaryContent(new byte[]{1, 2, 3});
    }

    /** No country location, or an ambiguous name: resolve() returns null and we skip. */
    @Test
    void upload_skipsSharePointEntirely_whenTheFolderCannotBeResolved() throws IOException {
        when(employeeFolderResolver.resolve(PAYS_ID, USER_ID)).thenReturn(null);

        DocumentUploadResponseDto dto =
                service.upload(PROFILE_ID, pdf("contrat.pdf"), "CONTRACT", null, null, null);

        assertThat(dto).isNotNull();
        verifyNoInteractions(graphSharePointService);
        verify(documentRepository).save(any());
    }

    /** Graph exploding is not the caller's problem — the local save already happened. */
    @Test
    void upload_neverThrows_whenTheMirrorBlowsUp() throws IOException {
        givenFolderResolves();
        when(graphSharePointService.uploadDocument(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));

        assertThatCode(() -> service.upload(PROFILE_ID, pdf("contrat.pdf"), "CONTRACT", null, null, null))
                .doesNotThrowAnyException();
        verify(documentRepository).save(any());
    }

    // ── Download: local cache first, SharePoint as the fallback ───────────────

    @Test
    void download_servesTheLocalFileWithoutTouchingSharePoint() throws IOException {
        Path local = tempDir.resolve(PROFILE_ID.toString()).resolve("stored.pdf");
        Files.createDirectories(local.getParent());
        Files.write(local, new byte[]{9, 9, 9});
        givenDocumentOnDisk(local.toString());

        var payload = service.download(PROFILE_ID, DOC_ID);

        assertThat(payload.resource().contentLength()).isEqualTo(3);
        verify(graphSharePointService, never()).downloadFile(anyString());
    }

    /**
     * The case that matters operationally: STORAGE_PATH has no volume, so a container
     * recreate wipes the local file. Before the mirror, the document was simply gone.
     */
    @Test
    void download_restoresFromSharePoint_whenTheLocalFileIsGone() throws IOException {
        Path missing = tempDir.resolve(PROFILE_ID.toString()).resolve("wiped.pdf");
        givenDocumentOnDisk(missing.toString());
        givenFolderResolves();
        when(graphSharePointService.downloadFile(
                "Tunisia/01_HR/01_Contracts-Employment/Jean Dupont/Employment Contracts & Amendments/"
                        + DOC_ID + "_contrat.pdf"))
                .thenReturn(Optional.of(new byte[]{7, 7, 7, 7}));

        var payload = service.download(PROFILE_ID, DOC_ID);

        assertThat(payload.resource().contentLength()).isEqualTo(4);
        // Re-cached, so the next download is local again.
        assertThat(missing).exists().hasBinaryContent(new byte[]{7, 7, 7, 7});
    }

    /** Gone from disk and absent from SharePoint is still a 404, not a 500. */
    @Test
    void download_reportsNotFound_whenNeitherCopyExists() {
        Path missing = tempDir.resolve(PROFILE_ID.toString()).resolve("wiped.pdf");
        givenDocumentOnDisk(missing.toString());
        givenFolderResolves();
        when(graphSharePointService.downloadFile(anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> service.download(PROFILE_ID, DOC_ID))
                .isInstanceOf(AppException.class);
    }

    private void givenDocumentOnDisk(String fileUrl) {
        EmployeeDocument doc = EmployeeDocument.builder()
                .id(DOC_ID)
                .employeeProfileId(PROFILE_ID)
                .documentType("CONTRACT")
                .fileName("contrat.pdf")
                .fileUrl(fileUrl)
                .storageProvider("LOCAL")
                .isDeleted(false)
                .build();
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.findByEmployeeProfileId(PROFILE_ID)).thenReturn(List.of(doc));
    }
}
