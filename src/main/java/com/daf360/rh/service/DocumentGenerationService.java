package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.GeneratedDocument;
import com.daf360.rh.domain.RequestTypeCatalog;
import com.daf360.rh.dto.requests.GeneratedDocumentResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates PDF documents for approved employee requests of category DOCUMENT.
 *
 * Template format: plain text file containing {{placeholders}}.
 * Supported placeholders:
 *   {{fullName}}   → Users.fullName
 *   {{hireDate}}   → employee_profiles.hire_date (dd/MM/yyyy)
 *   {{entity}}     → pays.french_label (company/entity name)
 *   {{date}}       → today dd/MM/yyyy
 *   {{position}}   → employee_profiles.grade (or empty)
 *   {{email}}      → Users.email
 *
 * Output: A4 PDF stored at FILE_STORAGE_PATH/documents/{requestId}/{uuid}.pdf.
 * A generated_documents record is created with a unique verification_code.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DocumentGenerationService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GeneratedDocumentRepository docRepo;
    private final AppProperties               props;
    private final JdbcTemplate                jdbcTemplate;

    // ── Generate ──────────────────────────────────────────────────────────────

    public GeneratedDocumentResponseDto generate(EmployeeRequest request,
                                                  RequestTypeCatalog type,
                                                  EmployeeProfile profile,
                                                  Long actorUserId) {
        Map<String, String> vars = buildVariables(profile);
        String content = resolveTemplate(type.getDocumentTemplateUrl(), type.getDisplayNameFr(), vars);
        String fileUrl  = writePdf(request.getId(), content);

        String verificationCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        GeneratedDocument doc = GeneratedDocument.builder()
                .employeeRequestId(request.getId())
                .documentType(type.getTypeCode())
                .fileUrl(fileUrl)
                .verificationCode(verificationCode)
                .generatedAt(OffsetDateTime.now(PARIS))
                .generatedBy(actorUserId)
                .build();

        GeneratedDocument saved = docRepo.save(doc);
        log.info("Generated document id={} requestId={} type={} file={}",
                saved.getId(), request.getId(), type.getTypeCode(), fileUrl);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<GeneratedDocumentResponseDto> listForRequest(Long requestId) {
        return docRepo.findByEmployeeRequestIdOrderByGeneratedAtDesc(requestId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public GeneratedDocumentResponseDto verify(String code) {
        return docRepo.findByVerificationCode(code)
                .map(this::toDto)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Aucun document trouvé pour le code: " + code));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, String> buildVariables(EmployeeProfile profile) {
        String fullName = jdbcTemplate.queryForObject(
                "SELECT ISNULL(fullName,'') FROM [dbo].[Users] WHERE id = ?",
                String.class, profile.getUserId());
        String email = jdbcTemplate.queryForObject(
                "SELECT ISNULL(email,'') FROM [dbo].[Users] WHERE id = ?",
                String.class, profile.getUserId());
        String entity = jdbcTemplate.queryForObject(
                "SELECT ISNULL(french_label,'') FROM [dbo].[pays] WHERE id = ?",
                String.class, profile.getPaysId());

        String hireDate = profile.getHireDate() != null
                ? profile.getHireDate().format(FR_DATE) : "";
        String today = LocalDate.now(PARIS).format(FR_DATE);

        return Map.of(
                "{{fullName}}", fullName != null ? fullName : "",
                "{{email}}",    email    != null ? email    : "",
                "{{entity}}",   entity   != null ? entity   : props.getCompanyName(),
                "{{hireDate}}", hireDate,
                "{{date}}",     today,
                "{{position}}", profile.getGrade() != null ? profile.getGrade().getLabelFr() : ""
        );
    }

    private String resolveTemplate(String templateUrl, String fallbackTitle,
                                    Map<String, String> vars) {
        String raw;
        if (templateUrl != null && !templateUrl.isBlank()) {
            try {
                raw = Files.readString(Paths.get(templateUrl), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                log.warn("Cannot read template at {}: {} — using fallback", templateUrl, ex.getMessage());
                raw = defaultTemplate(fallbackTitle);
            }
        } else {
            raw = defaultTemplate(fallbackTitle);
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            raw = raw.replace(e.getKey(), e.getValue());
        }
        return raw;
    }

    private String defaultTemplate(String title) {
        return """
                %s — {{entity}}
                =====================================

                Nous soussignés, certifions que {{fullName}},
                employé(e) depuis le {{hireDate}}, occupe le poste de {{position}}.

                La présente attestation est délivrée à la demande de l'intéressé(e)
                pour servir et valoir ce que de droit.

                Fait le {{date}}.

                La Direction des Ressources Humaines
                """.formatted(title);
    }

    /** Writes the text content to a PDF using PDFBox 3.x and returns the stored path. */
    private String writePdf(Long requestId, String content) {
        try {
            Path dir = Paths.get(props.getStoragePath(), "documents", String.valueOf(requestId));
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + ".pdf";
            Path dest = dir.resolve(filename);

            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                PDType1Font font     = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float margin = 50;
                    float y      = PDRectangle.A4.getHeight() - margin;
                    float lineH  = 16;

                    for (String line : content.split("\\r?\\n")) {
                        if (y < margin) {
                            cs.endText();
                            PDPage newPage = new PDPage(PDRectangle.A4);
                            doc.addPage(newPage);
                            cs.beginText();
                            y = PDRectangle.A4.getHeight() - margin;
                        }

                        boolean isHeader = line.startsWith("===") || line.isBlank();
                        cs.beginText();
                        cs.setFont(isHeader ? fontBold : font, isHeader ? 10 : 11);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(sanitize(line));
                        cs.endText();
                        y -= lineH;
                    }
                }
                doc.save(dest.toFile());
            }
            return dest.toString();
        } catch (IOException ex) {
            log.error("PDF generation failed for requestId={}: {}", requestId, ex.getMessage());
            throw new AppException(ErrorCode.DOCUMENT_GENERATION_FAILED,
                    "Échec génération PDF: " + ex.getMessage());
        }
    }

    /** Strip characters that PDType1Font cannot encode (latin-1 range only). */
    private String sanitize(String text) {
        if (text == null) return "";
        // Replace common French chars that are outside Winansi but in PDFBox standard encoding
        return text
                .replace("’", "'")
                .replace("‘", "'")
                .replace("“", "\"")
                .replace("”", "\"")
                .replace("–", "-")
                .replace("—", "-");
    }

    GeneratedDocumentResponseDto toDto(GeneratedDocument d) {
        GeneratedDocumentResponseDto dto = new GeneratedDocumentResponseDto();
        dto.setId(d.getId());
        dto.setEmployeeRequestId(d.getEmployeeRequestId());
        dto.setDocumentType(d.getDocumentType());
        dto.setFileUrl(d.getFileUrl());
        dto.setVerificationCode(d.getVerificationCode());
        dto.setGeneratedAt(d.getGeneratedAt());
        dto.setGeneratedBy(d.getGeneratedBy());
        return dto;
    }
}
