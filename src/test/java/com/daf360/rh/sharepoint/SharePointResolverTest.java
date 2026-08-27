package com.daf360.rh.sharepoint;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.EmployeeSharePointFolderStore;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.ResolutionSource;
import com.daf360.rh.service.sharepoint.SharePointLocationService;
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

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolver's contract, one test per outcome.
 *
 * <p>Written against the specific failures that motivated it: a working integration that had
 * simply never been asked for one employee's photo, six branches that all returned bare null,
 * and a folder naming deviation ("Bilel ZEDINI-CDI-ARX tunisie") that must be fixable by hand
 * without a later cache refresh silently undoing the fix.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharePointResolverTest {

    private static final Long PROFILE = 12L;
    private static final Long USER    = 46L;
    private static final Long PAYS    = 179L;

    private static final String PHOTO_TPL =
            "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
    private static final String PAYSLIP_TPL =
            "Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/01_Pay-Slip/{year}";

    @Mock private EmployeeProfileRepository        profileRepository;
    @Mock private EmployeeFolderResolver           employeeFolderResolver;
    @Mock private SharePointLocationService        locationService;
    @Mock private EmployeeSharePointFolderStore    folderStore;
    @Mock private GraphSharePointService           graph;

    @InjectMocks private SharePointResolver resolver;

    @BeforeEach
    void setUp() {
        EmployeeProfile profile = EmployeeProfile.builder()
                .id(PROFILE).userId(USER).paysId(PAYS).build();
        when(profileRepository.findById(PROFILE)).thenReturn(Optional.of(profile));
        when(employeeFolderResolver.fullNameOf(USER)).thenReturn("Bilel ZEDINI");
        when(employeeFolderResolver.normalize("Bilel ZEDINI")).thenReturn("Bilel ZEDINI");
        when(employeeFolderResolver.isAmbiguous("Bilel ZEDINI", PAYS)).thenReturn(false);
        when(folderStore.find(anyLong(), any())).thenReturn(Optional.empty());
        when(graph.isConfigured()).thenReturn(true);
    }

    @Test
    void discoversAndCachesAFolderThatExists() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(graph.folderExists(anyString())).thenReturn(true);

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PHOTO);

        assertThat(loc.status()).isEqualTo(SharePointStatus.FOUND);
        assertThat(loc.path())
                .isEqualTo("Tunisia/01_HR/01_Contracts-Employment/Bilel ZEDINI/Identity Documents");
        assertThat(loc.basePath()).isEqualTo(loc.path());
        assertThat(loc.employeeFolder()).isEqualTo("Bilel ZEDINI");
        verify(folderStore).saveDiscovered(PROFILE, DocKind.PHOTO, "Bilel ZEDINI",
                SharePointStatus.FOUND, null);
    }

    /** A year-scoped kind is checked at the folder HOLDING the year folders: the current
     *  year's folder may legitimately not exist yet, which is an empty list, not a fault. */
    @Test
    void yearScopedKindKeepsTheYearTokenAndVerifiesTheParent() {
        when(locationService.templateFor(PAYS, DocKind.PAYSLIP)).thenReturn(Optional.of(PAYSLIP_TPL));
        when(graph.folderExists("Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI/01_Pay-Slip"))
                .thenReturn(true);

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PAYSLIP);

        assertThat(loc.status()).isEqualTo(SharePointStatus.FOUND);
        assertThat(loc.path()).endsWith("/01_Pay-Slip/{year}");
        assertThat(loc.basePath()).endsWith("/01_Pay-Slip");
    }

    /** The payroll tree does not exist yet, so this is the expected answer today — and it must
     *  be a clean, explained degrade rather than an error. */
    @Test
    void reportsFolderMissingWhenTheTreeIsNotThereYet() {
        when(locationService.templateFor(PAYS, DocKind.PAYSLIP)).thenReturn(Optional.of(PAYSLIP_TPL));
        when(graph.folderExists(anyString())).thenReturn(false);

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PAYSLIP);

        assertThat(loc.status()).isEqualTo(SharePointStatus.FOLDER_MISSING);
        assertThat(loc.detail()).contains("01_Pay-Slip");
        verify(folderStore).saveDiscovered(eq(PROFILE), eq(DocKind.PAYSLIP), isNull(),
                eq(SharePointStatus.FOLDER_MISSING), anyString());
    }

    /**
     * Graph credentials absent must not read as "the folder is missing": one is fixed with an
     * environment variable, the other by hunting through SharePoint. Conflating them is what
     * sent this investigation down the wrong path once already.
     */
    @Test
    void distinguishesAnUnconfiguredIntegrationFromAMissingFolder() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(graph.folderExists(anyString())).thenReturn(false);
        when(graph.isConfigured()).thenReturn(false);

        assertThat(resolver.resolve(PROFILE, DocKind.PHOTO).status())
                .isEqualTo(SharePointStatus.UNAVAILABLE);
    }

    /** A country with no path configured is a normal state (Egypt has no payroll tree), so it
     *  resolves to NO_CONFIG without ever touching Graph. */
    @Test
    void reportsNoConfigWithoutCallingGraph() {
        when(locationService.templateFor(PAYS, DocKind.PAYSLIP)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(PROFILE, DocKind.PAYSLIP).status())
                .isEqualTo(SharePointStatus.NO_CONFIG);
        verify(graph, never()).folderExists(anyString());
        verify(graph, never()).listFolders(anyString());
    }

    @Test
    void refusesAnEmployeeWhoseNameIsSharedWithAColleague() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(employeeFolderResolver.isAmbiguous("Bilel ZEDINI", PAYS)).thenReturn(true);

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PHOTO);

        assertThat(loc.status()).isEqualTo(SharePointStatus.AMBIGUOUS_EMPLOYEE);
        verify(graph, never()).folderExists(anyString());
    }

    @Test
    void reportsNoNameWhenTheEmployeeHasNoUsableFullName() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(employeeFolderResolver.fullNameOf(USER)).thenReturn("   ");
        when(employeeFolderResolver.normalize("   ")).thenReturn(null);

        assertThat(resolver.resolve(PROFILE, DocKind.PHOTO).status())
                .isEqualTo(SharePointStatus.NO_NAME);
    }

    // ── Caching ───────────────────────────────────────────────────────────────

    /**
     * The whole point of the negative cache: an employee with no documents must not cost Graph
     * calls on every render. Before this, a directory page of a hundred such employees issued
     * roughly five hundred requests.
     */
    @Test
    void trustsARememberedFailureWithoutCallingGraph() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(folderStore.find(PROFILE, DocKind.PHOTO)).thenReturn(Optional.of(
                new EmployeeSharePointFolderStore.CachedFolder(
                        PROFILE, DocKind.PHOTO, null, ResolutionSource.DISCOVERED,
                        SharePointStatus.FOLDER_MISSING, OffsetDateTime.now().minusHours(1),
                        "dossier introuvable")));

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PHOTO);

        assertThat(loc.status()).isEqualTo(SharePointStatus.FOLDER_MISSING);
        verify(graph, never()).folderExists(anyString());
    }

    /** Forcing is how an operator sees a SharePoint correction immediately instead of waiting
     *  out the 24h negative window. */
    @Test
    void forceBypassesARememberedFailure() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(folderStore.find(PROFILE, DocKind.PHOTO)).thenReturn(Optional.of(
                new EmployeeSharePointFolderStore.CachedFolder(
                        PROFILE, DocKind.PHOTO, null, ResolutionSource.DISCOVERED,
                        SharePointStatus.FOLDER_MISSING, OffsetDateTime.now().minusMinutes(5),
                        "dossier introuvable")));
        when(graph.folderExists(anyString())).thenReturn(true);

        assertThat(resolver.resolve(PROFILE, DocKind.PHOTO, true).status())
                .isEqualTo(SharePointStatus.FOUND);
    }

    /** An expired negative entry is retried rather than trusted forever. */
    @Test
    void rediscoversOnceTheNegativeWindowHasElapsed() {
        when(locationService.templateFor(PAYS, DocKind.PHOTO)).thenReturn(Optional.of(PHOTO_TPL));
        when(folderStore.find(PROFILE, DocKind.PHOTO)).thenReturn(Optional.of(
                new EmployeeSharePointFolderStore.CachedFolder(
                        PROFILE, DocKind.PHOTO, null, ResolutionSource.DISCOVERED,
                        SharePointStatus.FOLDER_MISSING, OffsetDateTime.now().minusDays(2),
                        "dossier introuvable")));
        when(graph.folderExists(anyString())).thenReturn(true);

        assertThat(resolver.resolve(PROFILE, DocKind.PHOTO).status())
                .isEqualTo(SharePointStatus.FOUND);
    }

    /**
     * The rule that makes a manual override worth having. "Bilel ZEDINI-CDI-ARX tunisie" is a
     * real folder no convention predicts; if a later refresh could overwrite the correction,
     * the override would appear to work and then silently stop, which is worse than not having
     * it at all. It wins even under force, and without a Graph round trip.
     */
    @Test
    void manualOverrideWinsAndIsNeverRediscovered() {
        when(locationService.templateFor(PAYS, DocKind.PAYSLIP)).thenReturn(Optional.of(PAYSLIP_TPL));
        when(folderStore.find(PROFILE, DocKind.PAYSLIP)).thenReturn(Optional.of(
                new EmployeeSharePointFolderStore.CachedFolder(
                        PROFILE, DocKind.PAYSLIP, "Bilel ZEDINI-CDI-ARX tunisie",
                        ResolutionSource.MANUAL, SharePointStatus.FOUND,
                        OffsetDateTime.now().minusYears(1), null)));

        SharePointResolver.ResolvedLocation loc = resolver.resolve(PROFILE, DocKind.PAYSLIP, true);

        assertThat(loc.status()).isEqualTo(SharePointStatus.FOUND);
        assertThat(loc.employeeFolder()).isEqualTo("Bilel ZEDINI-CDI-ARX tunisie");
        assertThat(loc.source()).isEqualTo(ResolutionSource.MANUAL);
        assertThat(loc.basePath()).isEqualTo(
                "Tunisia/01_HR/03_Payroll-Admin/Bilel ZEDINI-CDI-ARX tunisie/01_Pay-Slip");
        verify(graph, never()).folderExists(anyString());
        verify(folderStore, never()).saveDiscovered(anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void aMissingProfileIsReportedRatherThanThrown() {
        when(profileRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(999L, DocKind.PHOTO).status())
                .isEqualTo(SharePointStatus.NO_CONFIG);
    }
}
