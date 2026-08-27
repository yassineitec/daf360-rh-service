package com.daf360.rh.sharepoint;

import com.daf360.rh.service.sharepoint.DocKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Template validation. Pinned here because the admin form and a hand-written SQL insert both
 * go through it, and a template that survives validation but cannot resolve produces the worst
 * kind of bug report: "the folder is missing" when the fault is one bad row.
 */
class DocKindTest {

    private static final String PHOTO_OK =
            "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents";
    private static final String PAYSLIP_OK =
            "Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/01_Pay-Slip/{year}";

    @Test
    void validTemplatesReportNoProblems() {
        assertThat(DocKind.PHOTO.validateTemplate(PHOTO_OK)).isEmpty();
        assertThat(DocKind.PAYSLIP.validateTemplate(PAYSLIP_OK)).isEmpty();
        assertThat(DocKind.SALARY_CERTIFICATE.validateTemplate(
                "Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/02_Salary-Certificate")).isEmpty();
    }

    /** Without the employee token every employee resolves to the same folder — one person's
     *  documents shown to everybody. The single most important rule here. */
    @Test
    void rejectsATemplateWithNoEmployeeFolderToken() {
        assertThat(DocKind.PHOTO.validateTemplate("Tunisia/01_HR/01_Contracts-Employment"))
                .contains("SHAREPOINT.TEMPLATE.MISSING_EMPLOYEE_FOLDER");
    }

    @Test
    void yearTokenIsRequiredExactlyWhereTheKindIsYearScoped() {
        assertThat(DocKind.PAYSLIP.validateTemplate(
                "Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/01_Pay-Slip"))
                .contains("SHAREPOINT.TEMPLATE.YEAR_REQUIRED");

        assertThat(DocKind.PHOTO.validateTemplate(PHOTO_OK.replace("Identity Documents", "{year}")))
                .contains("SHAREPOINT.TEMPLATE.YEAR_NOT_ALLOWED");
    }

    @Test
    void rejectsUnknownTokens() {
        assertThat(DocKind.PHOTO.validateTemplate(
                "Tunisia/{country}/{employeeFolder}/Identity Documents"))
                .contains("SHAREPOINT.TEMPLATE.UNKNOWN_TOKEN");
    }

    /** A path that walks upwards escapes the configured tree; one that starts, ends or doubles
     *  a separator builds an empty segment Graph rejects. */
    @Test
    void rejectsMalformedPaths() {
        assertThat(DocKind.PHOTO.validateTemplate("/Tunisia/{employeeFolder}/x"))
                .contains("SHAREPOINT.TEMPLATE.INVALID_PATH");
        assertThat(DocKind.PHOTO.validateTemplate("Tunisia/{employeeFolder}/x/"))
                .contains("SHAREPOINT.TEMPLATE.INVALID_PATH");
        assertThat(DocKind.PHOTO.validateTemplate("Tunisia//{employeeFolder}/x"))
                .contains("SHAREPOINT.TEMPLATE.INVALID_PATH");
        assertThat(DocKind.PHOTO.validateTemplate("Tunisia/../{employeeFolder}/x"))
                .contains("SHAREPOINT.TEMPLATE.INVALID_PATH");
    }

    @Test
    void blankTemplateIsReportedOnceAndNothingElseIsGuessed() {
        assertThat(DocKind.PHOTO.validateTemplate(null))
                .containsExactly("SHAREPOINT.TEMPLATE.BLANK");
        assertThat(DocKind.PHOTO.validateTemplate("   "))
                .containsExactly("SHAREPOINT.TEMPLATE.BLANK");
    }

    /** A doc_kind row can be prepared in the database before the build that consumes it ships,
     *  so an unrecognised value must read as "nothing configured", never as an error. */
    @Test
    void fromIsLenientAndNeverThrows() {
        assertThat(DocKind.from(" payslip ")).contains(DocKind.PAYSLIP);
        assertThat(DocKind.from("PHOTO")).contains(DocKind.PHOTO);
        assertThat(DocKind.from("CONTRACT_NOT_YET_SHIPPED")).isEmpty();
        assertThat(DocKind.from(null)).isEmpty();
        assertThat(DocKind.from("")).isEmpty();
    }

    @Test
    void onlyPayslipIsYearScopedToday() {
        assertThat(DocKind.PAYSLIP.isYearScoped()).isTrue();
        assertThat(DocKind.PHOTO.isYearScoped()).isFalse();
        assertThat(DocKind.SALARY_CERTIFICATE.isYearScoped()).isFalse();
    }
}
