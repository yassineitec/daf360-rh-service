package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.pdf.NumberToWordsEn;
import com.daf360.rh.service.pdf.NumberToWordsFr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The salary-advance debt contract (spec §5–6, rh V128). payroll-service owns the advance;
 * RH owns the employee's folder and the templates, so RH:
 *
 *  - renders the contract from the country's « Contrat d'avance sur salaire » template
 *    (Administration → Modèles de documents) through the existing HTML→PDF path — no change
 *    to pdf-service — and files it as a CONTRAT_AVANCE_SALAIRE employee document;
 *  - files the signed, legalised scan the DAF uploads, under the same type.
 *
 * Both land in the employee's documents tab and, through the usual mirror, in their SharePoint
 * folder « {employeeFolder}/Salary Advances » (sharepoint_locations, editable per country).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryAdvanceContractService {

    public static final String TEMPLATE_NAME = "Contrat d'avance sur salaire";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmployeeProfileRepository profileRepo;
    private final DocumentTemplateService   templateService;
    private final EmployeeDocumentService   documentService;

    /** What payroll sends at approval (mirror of payroll's HrAdvanceClient.ContractRequest). */
    public record ContractRequest(
            Long               advanceId,
            Long               userId,
            Long               paysId,
            String             currency,
            BigDecimal         grantedAmount,
            Integer            installments,
            BigDecimal         monthlyAmount,
            LocalDate          effectiveDate,
            String             firstDeductionMonth,
            String             lastDeductionMonth,
            List<ScheduleLine> schedule,
            Long               generatedBy
    ) {}

    public record ScheduleLine(Integer seq, String month, BigDecimal amount) {}

    public record DocumentRef(Long documentId, String fileName) {}

    public DocumentRef generate(Long advanceId, ContractRequest req) throws IOException {
        if (req.grantedAmount() == null || req.installments() == null || req.effectiveDate() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Conditions de l'avance incomplètes");
        }
        EmployeeProfile profile = profileOf(req.userId());
        Long paysId = profile.getPaysId();
        String lang = templateService.activeLang(TEMPLATE_NAME, paysId).orElse("fr");
        boolean en = "en".equalsIgnoreCase(lang);
        Locale locale = en ? Locale.ENGLISH : Locale.FRENCH;
        String currency = req.currency() == null ? "" : req.currency();

        Map<String, String> ctx = new HashMap<>();
        ctx.put("document.ref", "RH-AVS-" + LocalDate.now().getYear() + "-" + String.format("%05d", advanceId));
        ctx.put("document.verificationCode", UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        ctx.put("advance.grantedAmount", money(req.grantedAmount()));
        ctx.put("advance.grantedAmountInWords", en ? NumberToWordsEn.convert(req.grantedAmount())
                                                   : NumberToWordsFr.convert(req.grantedAmount()));
        ctx.put("advance.currency", currency);
        ctx.put("advance.effectiveDate", req.effectiveDate().format(DATE));
        ctx.put("advance.installments", String.valueOf(req.installments()));
        ctx.put("advance.monthlyAmount", money(req.monthlyAmount()));
        ctx.put("advance.firstDeductionMonth", monthLabel(req.firstDeductionMonth(), locale));
        ctx.put("advance.lastDeductionMonth", monthLabel(req.lastDeductionMonth(), locale));
        ctx.put("advance.scheduleTable", scheduleTable(req.schedule(), currency, locale, en));

        byte[] pdf = templateService.renderByName(TEMPLATE_NAME, paysId, profile.getId(), lang, ctx)
                .orElseThrow(() -> new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Aucun modèle « " + TEMPLATE_NAME + " » actif pour ce pays — à créer dans Modèles de documents"));

        String fileName = "Contrat avance sur salaire " + advanceId + " - a signer.pdf";
        DocumentUploadResponseDto doc = documentService.storeFile(profile.getId(),
                EmployeeDocumentService.SALARY_ADVANCE_CONTRACT, fileName, "application/pdf", pdf,
                "Avance sur salaire n°" + advanceId + " — contrat généré, à signer et légaliser", req.generatedBy());
        log.info("Salary-advance contract generated: advance={} profile={} document={}", advanceId, profile.getId(), doc.getId());
        return new DocumentRef(doc.getId(), fileName);
    }

    public DocumentRef archiveSigned(Long advanceId, Long userId, Long uploadedBy,
                                     byte[] bytes, String originalName, String contentType) throws IOException {
        EmployeeProfile profile = profileOf(userId);
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.')).toLowerCase()
                : ".pdf";
        String fileName = "Contrat avance sur salaire " + advanceId + " - signe" + ext;
        DocumentUploadResponseDto doc = documentService.storeFile(profile.getId(),
                EmployeeDocumentService.SALARY_ADVANCE_CONTRACT, fileName, contentType, bytes,
                "Avance sur salaire n°" + advanceId + " — contrat signé et légalisé", uploadedBy);
        log.info("Signed salary-advance contract archived: advance={} profile={} document={}", advanceId, profile.getId(), doc.getId());
        return new DocumentRef(doc.getId(), fileName);
    }

    /** Only a CONTRAT_AVANCE_SALAIRE of this employee — the internal key reads nothing else. */
    public EmployeeDocumentService.DownloadPayload download(Long userId, Long documentId) {
        EmployeeProfile profile = profileOf(userId);
        String type = documentService.typeOf(profile.getId(), documentId);
        if (!EmployeeDocumentService.SALARY_ADVANCE_CONTRACT.equals(type)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Document introuvable: id=" + documentId);
        }
        return documentService.download(profile.getId(), documentId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private EmployeeProfile profileOf(Long userId) {
        return profileRepo.findByUserId(userId)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Profil introuvable pour l'utilisateur " + userId));
    }

    /** "1 500" or "1 500,250": space-grouped, decimals only when there are some (up to 3). */
    private static String money(BigDecimal v) {
        if (v == null) return "";
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.FRENCH);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator(',');
        BigDecimal scaled = v.setScale(3, RoundingMode.HALF_UP);
        boolean whole = scaled.remainder(BigDecimal.ONE).signum() == 0;
        DecimalFormat f = new DecimalFormat(whole ? "#,##0" : "#,##0.000", s);
        return f.format(scaled);
    }

    private static String monthLabel(String yyyyMm, Locale locale) {
        if (yyyyMm == null || yyyyMm.isBlank()) return "";
        try {
            return YearMonth.parse(yyyyMm).atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale));
        } catch (Exception e) {
            return yyyyMm;
        }
    }

    private static String scheduleTable(List<ScheduleLine> lines, String currency, Locale locale, boolean en) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<table class=\"s\"><tr><th>")
                .append(en ? "No." : "N°").append("</th><th>")
                .append(en ? "Payroll month" : "Mois de paie").append("</th><th>")
                .append(en ? "Amount" : "Montant").append("</th></tr>");
        for (ScheduleLine l : lines) {
            html.append("<tr><td>").append(l.seq()).append("</td><td>")
                .append(monthLabel(l.month(), locale)).append("</td><td>")
                .append(money(l.amount())).append(' ').append(currency).append("</td></tr>");
        }
        return html.append("</table>").toString();
    }
}
