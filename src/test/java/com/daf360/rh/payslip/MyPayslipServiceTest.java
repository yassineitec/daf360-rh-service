package com.daf360.rh.payslip;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.payslip.MyPayslipHistoryDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.payslip.MyPayslipService;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.ResolutionSource;
import com.daf360.rh.service.sharepoint.SharePointResolver;
import com.daf360.rh.service.sharepoint.SharePointStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules that matter here are all about WHICH folder is read, not about formatting: the
 * download endpoint takes a file name from the client, so every test below is a variation on
 * "can a caller reach bytes that are not in their own payslip folder".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyPayslipServiceTest {

    private static final long USER_ID    = 42L;
    private static final long PROFILE_ID = 7L;
    private static final String TEMPLATE =
            "Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip/{year}";
    private static final String FOLDER_2026 =
            "Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip/2026";

    @Mock EmployeeProfileRepository profileRepository;
    @Mock SharePointResolver resolver;
    @Mock GraphSharePointService graph;
    @Mock EmployeeFolderResolver employeeFolderResolver;

    @InjectMocks MyPayslipService service;

    @BeforeEach
    void profileExists() {
        EmployeeProfile profile = new EmployeeProfile();
        profile.setId(PROFILE_ID);
        profile.setUserId(USER_ID);
        profile.setPaysId(1L);
        when(profileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(employeeFolderResolver.fullNameOf(USER_ID)).thenReturn("Bilel ZEDINI");
    }

    private void folderResolves() {
        when(resolver.resolve(PROFILE_ID, DocKind.PAYSLIP)).thenReturn(
                new SharePointResolver.ResolvedLocation(
                        SharePointStatus.FOUND, TEMPLATE,
                        "Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip",
                        "Bilel ZEDINI", ResolutionSource.DISCOVERED, null));
    }

    private void folderMisses(SharePointStatus status) {
        when(resolver.resolve(PROFILE_ID, DocKind.PAYSLIP)).thenReturn(
                new SharePointResolver.ResolvedLocation(
                        status, null, null, null, null, "pas de chemin"));
    }

    private static GraphSharePointService.RemoteFile file(String name) {
        return new GraphSharePointService.RemoteFile(name, "2026-02-01T10:00:00Z", 1024L, null);
    }

    // ── history ───────────────────────────────────────────────────────────────

    @Test
    void history_reportsStatusAndEmptyList_whenCountryHasNoPayrollTree() {
        folderMisses(SharePointStatus.NO_CONFIG);

        MyPayslipHistoryDto result = service.history(USER_ID);

        assertThat(result.status()).isEqualTo(SharePointStatus.NO_CONFIG);
        assertThat(result.items()).isEmpty();
        // The point of the status: no Graph call was made, and no exception was thrown either.
        verify(graph, never()).listFiles(anyString());
    }

    @Test
    void history_reportsFolderMissing_ratherThanFailing() {
        folderMisses(SharePointStatus.FOLDER_MISSING);

        assertThat(service.history(USER_ID).status()).isEqualTo(SharePointStatus.FOLDER_MISSING);
    }

    @Test
    void history_listsEveryYear_newestPeriodFirst() {
        folderResolves();
        when(resolver.years(any(), any())).thenReturn(List.of("2026", "2025"));
        when(graph.listFiles(FOLDER_2026)).thenReturn(List.of(
                file("Bilel_ZEDINI_Janvier_2026.PDF"),
                file("Bilel_ZEDINI_Mars_2026.PDF")));
        when(graph.listFiles("Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip/2025"))
                .thenReturn(List.of(file("Bilel_ZEDINI_Decembre_2025.PDF")));

        MyPayslipHistoryDto result = service.history(USER_ID);

        assertThat(result.items()).extracting(d -> d.year() + "-" + d.month())
                .containsExactly("2026-3", "2026-1", "2025-12");
    }

    @Test
    void history_skipsNonPdfEntriesAndNonYearFolders() {
        folderResolves();
        when(resolver.years(any(), any())).thenReturn(List.of("2026", "OLD"));
        when(graph.listFiles(FOLDER_2026)).thenReturn(List.of(
                file("Bilel_ZEDINI_Mars_2026.PDF"),
                file("notes.txt")));

        MyPayslipHistoryDto result = service.history(USER_ID);

        assertThat(result.items()).extracting(d -> d.fileName())
                .containsExactly("Bilel_ZEDINI_Mars_2026.PDF");
        verify(graph, never()).listFiles(
                "Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip/OLD");
    }

    @Test
    void history_keepsFileWhoseNameCannotBeParsed_fallingBackToTheYearFolder() {
        folderResolves();
        when(resolver.years(any(), any())).thenReturn(List.of("2024"));
        when(graph.listFiles("Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip/2024"))
                .thenReturn(List.of(file("bulletin final (2).pdf")));

        MyPayslipHistoryDto result = service.history(USER_ID);

        // Shown, not dropped: HR files these by hand, and a row the employee can recognise
        // beats an empty list. followsConvention is how an admin finds it to rename.
        assertThat(result.items()).singleElement().satisfies(d -> {
            assertThat(d.year()).isEqualTo(2024);
            assertThat(d.followsConvention()).isFalse();
        });
    }

    @Test
    void history_failsWhenTheUserHasNoEmployeeProfile() {
        when(profileRepository.findByUserId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(99L))
                .isInstanceOf(AppException.class);
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    void download_servesAFileThatIsInTheCallersOwnFolder() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026))
                .thenReturn(List.of(file("Bilel_ZEDINI_Mars_2026.PDF")));
        when(graph.downloadFile(FOLDER_2026 + "/Bilel_ZEDINI_Mars_2026.PDF"))
                .thenReturn(Optional.of(new byte[] { 1, 2, 3 }));

        MyPayslipService.PayslipFile result =
                service.download(USER_ID, 2026, "Bilel_ZEDINI_Mars_2026.PDF");

        assertThat(result.fileName()).isEqualTo("Bilel_ZEDINI_Mars_2026.PDF");
        assertThat(result.bytes()).hasSize(3);
    }

    @Test
    void download_refusesPathTraversalWithoutTouchingGraph() {
        for (String attempt : List.of(
                "../../../Tunisia/01_HR/03_Payroll-Admin/Sana MARZOUKI/01_Pay-Slip/2026/x.pdf",
                "..\\x.pdf",
                "Sana_MARZOUKI_Mars_2026.PDF/../../x.pdf",
                "x.pdf:stream")) {

            assertThatThrownBy(() -> service.download(USER_ID, 2026, attempt))
                    .as("attempt: %s", attempt)
                    .isInstanceOf(AppException.class);
        }
        verify(graph, never()).downloadFile(anyString());
        verify(graph, never()).listFiles(anyString());
    }

    @Test
    void download_refusesAnythingThatIsNotAPdf() {
        assertThatThrownBy(() -> service.download(USER_ID, 2026, "salaries.xlsx"))
                .isInstanceOf(AppException.class);
        verify(graph, never()).downloadFile(anyString());
    }

    @Test
    void download_refusesAFileThatIsNotInTheListing() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026))
                .thenReturn(List.of(file("Bilel_ZEDINI_Mars_2026.PDF")));

        // A perfectly well-formed name — belonging to somebody else. The listing is the check.
        assertThatThrownBy(() -> service.download(USER_ID, 2026, "Sana_MARZOUKI_Mars_2026.PDF"))
                .isInstanceOf(AppException.class);
        verify(graph, never()).downloadFile(anyString());
    }

    @Test
    void download_refusesWhenTheFolderDoesNotResolve() {
        folderMisses(SharePointStatus.UNAVAILABLE);

        assertThatThrownBy(() -> service.download(USER_ID, 2026, "Bilel_ZEDINI_Mars_2026.PDF"))
                .isInstanceOf(AppException.class);
        verify(graph, never()).listFiles(anyString());
    }

    @Test
    void download_usesTheRealSpellingWhenOnlyAccentsDiffer() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026))
                .thenReturn(List.of(file("Kods_CHÉRIF_Mars_2026.PDF")));
        when(graph.downloadFile(FOLDER_2026 + "/Kods_CHÉRIF_Mars_2026.PDF"))
                .thenReturn(Optional.of(new byte[] { 9 }));

        MyPayslipService.PayslipFile result =
                service.download(USER_ID, 2026, "Kods_CHERIF_Mars_2026.PDF");

        assertThat(result.fileName()).isEqualTo("Kods_CHÉRIF_Mars_2026.PDF");
    }

    @Test
    void download_refusesWhenTwoFilesFoldToTheRequestedName() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026)).thenReturn(List.of(
                file("Kods_CHÉRIF_Mars_2026.PDF"),
                file("Kods_CHERIF_Mars_2026.PDF")));

        // Neither matches literally (the extension case differs), both match once case and
        // accents are folded — a coin toss would serve the wrong file.
        assertThatThrownBy(() -> service.download(USER_ID, 2026, "Kods_CHERIF_Mars_2026.pdf"))
                .isInstanceOf(AppException.class);
        verify(graph, never()).downloadFile(anyString());
    }

    @Test
    void download_prefersAnExactMatchOverAFoldedOne() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026)).thenReturn(List.of(
                file("Kods_CHÉRIF_Mars_2026.PDF"),
                file("Kods_CHERIF_Mars_2026.PDF")));
        when(graph.downloadFile(FOLDER_2026 + "/Kods_CHERIF_Mars_2026.PDF"))
                .thenReturn(Optional.of(new byte[] { 5 }));

        MyPayslipService.PayslipFile result =
                service.download(USER_ID, 2026, "Kods_CHERIF_Mars_2026.PDF");

        assertThat(result.fileName()).isEqualTo("Kods_CHERIF_Mars_2026.PDF");
    }

    @Test
    void download_failsCleanlyWhenGraphCannotReadAFileItJustListed() {
        folderResolves();
        when(graph.listFiles(FOLDER_2026))
                .thenReturn(List.of(file("Bilel_ZEDINI_Mars_2026.PDF")));
        when(graph.downloadFile(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(USER_ID, 2026, "Bilel_ZEDINI_Mars_2026.PDF"))
                .isInstanceOf(AppException.class);
    }
}
