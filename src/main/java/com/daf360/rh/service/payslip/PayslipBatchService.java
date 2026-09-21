package com.daf360.rh.service.payslip;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.payslip.PayslipBatchResultDto;
import com.daf360.rh.dto.payslip.PayslipPageResultDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.SharePointLocationService;
import com.daf360.rh.service.sharepoint.SharePointPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Splits one monthly, multi-page payslip PDF (one employee per page, produced by an external
 * payroll system) into single-page PDFs and files each one into the matching employee's
 * SharePoint folder.
 *
 * <p>Matching is by the payroll software's own "Matricule" printed on each page (see
 * {@link PayslipMatriculeExtractor}), looked up against {@link EmployeeProfile#getPayrollMatricule()}
 * — a field distinct from this app's own {@code Users.employee_id}, see that field's javadoc.
 *
 * <p>Upload reuses exactly the same SharePoint machinery {@code PdfDocumentService} already
 * uses for generated attestations ({@link EmployeeFolderResolver}, {@link SharePointLocationService},
 * {@link GraphSharePointService}) rather than re-deriving folder paths — same ambiguous-name
 * guard, same "create the folder if missing" upload behaviour, same never-throws contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayslipBatchService {

    public static final String STATUS_SUCCESS      = "SUCCESS";
    public static final String STATUS_ERROR        = "ERROR";
    public static final String STATUS_UNIDENTIFIED = "UNIDENTIFIED";
    public static final String STATUS_DUPLICATE    = "DUPLICATE";

    private final EmployeeProfileRepository   profileRepository;
    private final EmployeeFolderResolver      employeeFolderResolver;
    private final SharePointLocationService   locationService;
    private final GraphSharePointService      graphSharePointService;

    /**
     * @param pdfBytes    the monthly batch PDF, one payslip per page
     * @param paysId      which country's employees/SharePoint tree to match against — a
     *                    matricule is only unique within one payroll run (one country), see
     *                    {@link EmployeeProfileRepository#findByPaysIdAndPayrollMatricule}
     * @param periodYear  the period this batch covers, e.g. 2026
     * @param periodMonth 1-12
     */
    @Transactional(readOnly = true)
    public PayslipBatchResultDto processBatch(byte[] pdfBytes, Long paysId,
                                               int periodYear, int periodMonth) throws IOException {
        List<PayslipPageResultDto> results = new ArrayList<>();
        Set<String> matriculesSeen = new HashSet<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            log.info("PayslipBatch: {} pages, pays={}, periode={}/{}",
                    totalPages, paysId, periodMonth, periodYear);

            for (int index = 0; index < totalPages; index++) {
                int pageNumber = index + 1;
                results.add(processPage(document, index, pageNumber, paysId,
                        periodYear, periodMonth, matriculesSeen));
            }

            long success      = count(results, STATUS_SUCCESS);
            long unidentified = count(results, STATUS_UNIDENTIFIED);
            long duplicate    = count(results, STATUS_DUPLICATE);
            long error        = count(results, STATUS_ERROR);
            log.info("PayslipBatch termine: success={}, error={}, unidentified={}, duplicate={}",
                    success, error, unidentified, duplicate);

            return PayslipBatchResultDto.builder()
                    .totalPages(totalPages)
                    .successCount((int) success)
                    .errorCount((int) error)
                    .unidentifiedCount((int) unidentified)
                    .duplicateCount((int) duplicate)
                    .details(results)
                    .build();
        }
    }

    private PayslipPageResultDto processPage(PDDocument document, int pageIndex, int pageNumber,
                                              Long paysId, int periodYear, int periodMonth,
                                              Set<String> matriculesSeen) {
        PayslipPageResultDto.PayslipPageResultDtoBuilder result =
                PayslipPageResultDto.builder().pageNumber(pageNumber);
        try {
            String matricule = extractMatricule(document, pageNumber);
            result.matricule(matricule);

            if (matricule == null) {
                log.warn("PayslipBatch page {}: aucun matricule trouve", pageNumber);
                return result.status(STATUS_UNIDENTIFIED)
                        .errorMessage("Matricule non trouve dans le texte de la page")
                        .build();
            }

            if (!matriculesSeen.add(matricule)) {
                log.warn("PayslipBatch page {}: matricule {} deja traite dans ce batch", pageNumber, matricule);
                return result.status(STATUS_DUPLICATE)
                        .errorMessage("Ce matricule a deja ete traite dans ce batch")
                        .build();
            }

            EmployeeProfile employee = profileRepository
                    .findByPaysIdAndPayrollMatricule(paysId, matricule)
                    .orElse(null);
            if (employee == null) {
                log.warn("PayslipBatch page {}: matricule {} introuvable pour pays {}",
                        pageNumber, matricule, paysId);
                return result.status(STATUS_ERROR)
                        .errorMessage("Matricule '" + matricule + "' non trouve dans la base de donnees")
                        .build();
            }

            String fullName = employeeFolderResolver.fullNameOf(employee.getUserId());
            result.employeeFullName(fullName);

            byte[] pageBytes = extractSinglePage(document, pageIndex);
            String sharePointUrl = uploadPayslip(employee, fullName, pageBytes,
                    periodYear, periodMonth, pageNumber);

            return result.status(STATUS_SUCCESS).sharePointUrl(sharePointUrl).build();

        } catch (Exception e) {
            log.error("PayslipBatch page {}: erreur inattendue", pageNumber, e);
            return result.status(STATUS_ERROR)
                    .errorMessage("Erreur inattendue : " + e.getMessage())
                    .build();
        }
    }

    /**
     * Text of one page, in VISUAL reading order (see {@link PayslipMatriculeExtractor} for why
     * that matters), then the matricule pulled out of it.
     */
    private String extractMatricule(PDDocument document, int pageNumber) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        String pageText = stripper.getText(document);
        return PayslipMatriculeExtractor.extract(pageText);
    }

    /** Copies one page into a brand-new single-page PDF and serialises it. */
    private byte[] extractSinglePage(PDDocument source, int pageIndex) throws IOException {
        try (PDDocument singlePage = new PDDocument()) {
            PDPage page = source.getPage(pageIndex);
            singlePage.importPage(page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            singlePage.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Uploads via the exact same folder-resolution + ambiguity-guard + upload machinery
     * {@code PdfDocumentService.saveGeneratedDocument} uses — see that method's javadoc for why
     * the employee folder name comes from {@code Users.fullName} rather than a rebuilt
     * first/last name. Never throws: a SharePoint failure here degrades to a null URL, reported
     * to the caller as part of the page's result (still SUCCESS at the matching level — the
     * split page was produced correctly, only the SharePoint deposit failed).
     *
     * @return the SharePoint URL, or null if not configured / ambiguous / upload failed
     */
    private String uploadPayslip(EmployeeProfile employee, String fullName, byte[] pageBytes,
                                  int periodYear, int periodMonth, int pageNumber) {
        Optional<String> template = locationService.templateFor(employee.getPaysId(), DocKind.PAYSLIP);
        if (template.isEmpty()) {
            log.info("PayslipBatch page {}: aucun chemin SharePoint configure pour le pays {} / PAYSLIP",
                    pageNumber, employee.getPaysId());
            return null;
        }

        String employeeFolder = employeeFolderResolver.normalize(fullName);
        if (employeeFolder == null) {
            log.warn("PayslipBatch page {}: pas de fullName exploitable pour l'utilisateur {}",
                    pageNumber, employee.getUserId());
            return null;
        }
        if (employeeFolderResolver.isAmbiguous(employeeFolder, employee.getPaysId())) {
            log.warn("PayslipBatch page {}: plusieurs employes partagent le nom de dossier '{}' — " +
                    "depot SharePoint ignore par securite", pageNumber, employeeFolder);
            return null;
        }

        String locationWithYear = template.get().replace(SharePointPaths.YEAR_TOKEN, String.valueOf(periodYear));
        String fileName = SharePointPaths.safeFileName(
                buildFileName(fullName, periodYear, periodMonth), "payslip.pdf");

        return graphSharePointService
                .uploadDocument(locationWithYear, employeeFolder, fileName, pageBytes)
                .orElse(null);
    }

    /** {@code FirstName_LASTNAME_Month_Year.pdf} — the convention {@link PaySlipFileName}
     * already parses, French month name to match its "agreed convention" test case. Multi-word
     * surnames (e.g. "Ali YASSINE BEL HAJ HOUMA") just get every space turned into an
     * underscore, same as the real examples that convention was written against. */
    private String buildFileName(String fullName, int periodYear, int periodMonth) {
        String monthFr = Month.of(periodMonth).getDisplayName(TextStyle.FULL, Locale.FRENCH);
        String monthCapitalized = monthFr.substring(0, 1).toUpperCase(Locale.FRENCH) + monthFr.substring(1);
        String namePart = fullName.trim().replaceAll("\\s+", "_");
        return namePart + "_" + monthCapitalized + "_" + periodYear + ".pdf";
    }

    private long count(List<PayslipPageResultDto> results, String status) {
        return results.stream().filter(r -> status.equals(r.getStatus())).count();
    }
}
