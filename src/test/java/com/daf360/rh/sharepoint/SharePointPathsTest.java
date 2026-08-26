package com.daf360.rh.sharepoint;

import com.daf360.rh.service.sharepoint.DocumentFolderMapping;
import com.daf360.rh.service.sharepoint.SharePointPaths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The path rules decide where files land in HR's real SharePoint tree, so they are pinned
 * here rather than left to a live test against the tenant.
 */
class SharePointPathsTest {

    private static final String TN_PHOTO =
            "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";

    // ── squash / sameSegment: the duplicate-folder bug ────────────────────────

    /**
     * The live tree holds "Abir  ESSAYEM" with two spaces while EmployeeFolderResolver
     * normalizes the name to one. Matching on the squashed form is what finds HR's folder
     * instead of creating a second one beside it.
     */
    @Test
    void sameSegment_matchesFoldersThatDifferOnlyBySpacingOrCase() {
        assertThat(SharePointPaths.sameSegment("Abir  ESSAYEM", "Abir ESSAYEM")).isTrue();
        assertThat(SharePointPaths.sameSegment(" Abir ESSAYEM ", "Abir ESSAYEM")).isTrue();
        assertThat(SharePointPaths.sameSegment("abir essayem",  "Abir ESSAYEM")).isTrue();
        assertThat(SharePointPaths.sameSegment("Identity documents", "Identity Documents")).isTrue();
    }

    /** Tolerance must stop at spacing and case — two different people stay different. */
    @Test
    void sameSegment_doesNotMatchDifferentNames() {
        assertThat(SharePointPaths.sameSegment("Abir ESSAYEM", "Abir ESSAYEMI")).isFalse();
        assertThat(SharePointPaths.sameSegment("Amira CHEBBI", "Amira CHEBBY")).isFalse();
        assertThat(SharePointPaths.sameSegment(null, "Abir ESSAYEM")).isFalse();
    }

    // ── employeeFolderBase: deriving the document root from the photo path ────

    @Test
    void employeeFolderBase_cutsAtTheEmployeeFolderPlaceholder() {
        assertThat(SharePointPaths.employeeFolderBase(TN_PHOTO))
                .isEqualTo("Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}");
    }

    @Test
    void employeeFolderBase_keepsTheCountryRootPrefix() {
        // V80 exists because the first version of these paths lacked the leading country
        // segment and wrote into the wrong level of the site.
        assertThat(SharePointPaths.employeeFolderBase(
                "EGY/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents"))
                .startsWith("EGY/");
    }

    /** No placeholder, blank, or null → the caller must skip SharePoint entirely. */
    @Test
    void employeeFolderBase_returnsNullWhenThereIsNothingToDeriveFrom() {
        assertThat(SharePointPaths.employeeFolderBase("Tunisia/01_HR")).isNull();
        assertThat(SharePointPaths.employeeFolderBase("   ")).isNull();
        assertThat(SharePointPaths.employeeFolderBase(null)).isNull();
    }

    // ── safeFileName: a name must never retarget the upload ───────────────────

    /**
     * A '/' in the name would move the file to another folder; the rest are characters
     * SharePoint refuses in an item name.
     */
    @Test
    void safeFileName_neutralisesPathSeparatorsAndIllegalCharacters() {
        assertThat(SharePointPaths.safeFileName("../../etc/passwd", "fallback"))
                .doesNotContain("/").doesNotContain("\\");
        assertThat(SharePointPaths.safeFileName("contrat:2026*.pdf", "fallback"))
                .isEqualTo("contrat_2026_.pdf");
        assertThat(SharePointPaths.safeFileName("re%port#1.pdf", "fallback"))
                .isEqualTo("re_port_1.pdf");
    }

    @Test
    void safeFileName_keepsOrdinaryNamesIntact() {
        assertThat(SharePointPaths.safeFileName("Contrat CDI signé.pdf", "fallback"))
                .isEqualTo("Contrat CDI signé.pdf");
    }

    /** Leading dots/spaces make an item SharePoint refuses to create. */
    @Test
    void safeFileName_fallsBackWhenNothingUsableRemains() {
        assertThat(SharePointPaths.safeFileName("...", "document-42")).isEqualTo("document-42");
        assertThat(SharePointPaths.safeFileName("   ", "document-42")).isEqualTo("document-42");
        assertThat(SharePointPaths.safeFileName(null,  "document-42")).isEqualTo("document-42");
    }

    // ── join ─────────────────────────────────────────────────────────────────

    @Test
    void join_producesNoEmptyOrDoubledSegments() {
        assertThat(SharePointPaths.join("a/", "/b/", "", null, "c")).isEqualTo("a/b/c");
        assertThat(SharePointPaths.join("Tunisia/01_HR", "HR Requests"))
                .isEqualTo("Tunisia/01_HR/HR Requests");
    }

    // ── DocumentFolderMapping ────────────────────────────────────────────────

    /** The four targets must match the real tree exactly — a typo creates a new folder. */
    @Test
    void subfolderFor_usesTheFolderNamesThatExistInTheTree() {
        assertThat(DocumentFolderMapping.subfolderFor("CONTRACT_SIGNED"))
                .isEqualTo("Employment Contracts & Amendments");
        assertThat(DocumentFolderMapping.subfolderFor("PASSPORT"))
                .isEqualTo("Identity Documents");
        assertThat(DocumentFolderMapping.subfolderFor("MEDICAL_CERTIFICATE"))
                .isEqualTo("Time Off & Leaves");
    }

    /**
     * The profile photo mirrors to Identity Documents (that is where TN_PHOTO points), so a
     * PHOTO uploaded through the documents tab lands beside it rather than in a second place.
     */
    @Test
    void subfolderFor_filesPhotoDocumentsBesideTheProfilePhoto() {
        assertThat(TN_PHOTO).endsWith(DocumentFolderMapping.subfolderFor("PHOTO"));
    }

    /** Unmapped types get our own folder rather than a guess inside an HR-curated one. */
    @Test
    void subfolderFor_sendsUnmappedTypesToTheDefaultFolder() {
        assertThat(DocumentFolderMapping.subfolderFor("CV"))
                .isEqualTo(DocumentFolderMapping.DEFAULT_SUBFOLDER);
        assertThat(DocumentFolderMapping.subfolderFor("OTHER"))
                .isEqualTo(DocumentFolderMapping.DEFAULT_SUBFOLDER);
        assertThat(DocumentFolderMapping.subfolderFor("SOMETHING_NEW"))
                .isEqualTo(DocumentFolderMapping.DEFAULT_SUBFOLDER);
        assertThat(DocumentFolderMapping.subfolderFor(null))
                .isEqualTo(DocumentFolderMapping.DEFAULT_SUBFOLDER);
    }

    @Test
    void subfolderFor_isCaseAndWhitespaceInsensitiveOnTheTypeCode() {
        assertThat(DocumentFolderMapping.subfolderFor(" contract "))
                .isEqualTo("Employment Contracts & Amendments");
    }
}
