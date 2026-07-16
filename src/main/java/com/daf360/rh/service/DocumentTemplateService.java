package com.daf360.rh.service;

import com.daf360.rh.common.DocumentVariableCatalog;
import com.daf360.rh.domain.DocumentTemplate;
import com.daf360.rh.dto.document.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.DocumentTemplateRepository;
import com.daf360.rh.service.pdf.NumberToWordsFr;
import com.daf360.rh.service.pdf.PdfClientService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DocumentTemplateService {

    private final DocumentTemplateRepository repo;
    private final PdfClientService           pdfClient;
    private final JdbcTemplate               jdbc;
    private final ObjectMapper               objectMapper;

    private static final DateTimeFormatter DATE_FR  = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern           VAR_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    // Month labels with accents — used for date.monthLabel (new catalog variable)
    private static final String[] MONTH_LABELS_FR = {
        "janvier","février","mars","avril","mai","juin",
        "juillet","août","septembre","octobre","novembre","décembre"
    };

    // Month labels without accents — used in long-form dates matching PdfDocumentService format
    private static final String[] MOIS_FR = {
        "janvier","fevrier","mars","avril","mai","juin",
        "juillet","aout","septembre","octobre","novembre","decembre"
    };

    // ── SQL ───────────────────────────────────────────────────────────────────

    private static final String EMPLOYEE_CTX_SQL =
        "SELECT ep.national_id, ep.cin_city, ep.cin_date, ep.hire_date, " +
        "       ep.probation_end_date, ep.contract_type, ep.salaire_net_rh, " +
        "       ep.gender, ep.rib, ep.iban, " +
        "       u.fullName AS full_name, u.username AS ms365_email, " +
        "       ISNULL(g.label_fr, '') AS grade, " +
        "       ISNULL(d.label_fr, '') AS discipline, " +
        "       p.french_label AS pays_label, p.iso_code, " +
        "       ISNULL(b.label_fr, '') AS bank_name, " +
        "       c.first_name, c.last_name " +
        "FROM [dbo].[employee_profiles] ep " +
        "JOIN [dbo].[Users]       u ON u.id = ep.user_id " +
        "JOIN [dbo].[pays]        p ON p.id = ep.pays_id " +
        "JOIN [dbo].[candidates]  c ON c.id = ep.candidate_id " +
        "LEFT JOIN [dbo].[grades]       g ON g.id = ep.grade_id " +
        "LEFT JOIN [dbo].[disciplines]  d ON d.id = ep.discipline_id " +
        "LEFT JOIN [dbo].[banks]        b ON b.id = ep.bank_id " +
        "WHERE ep.id = ?";

    private static final String DG_CTX_SQL =
        "SELECT cle, valeur FROM [dbo].[parameter_sets] " +
        "WHERE pays_id = ? AND cle IN " +
        "('DG_NAME','DG_CIN','DG_CIN_DATE','DG_CIN_CITY','DG_TITLE','COMPANY_NAME')";

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentTemplateDto> list(Long paysId, String category, boolean includeInactive) {
        List<DocumentTemplate> templates;
        if (category != null && !category.isBlank()) {
            templates = includeInactive
                ? repo.findByPaysIdAndCategoryOrderByNameAsc(paysId, category)
                : repo.findByPaysIdAndCategoryAndIsActiveTrueOrderByNameAsc(paysId, category);
        } else {
            templates = includeInactive
                ? repo.findByPaysIdOrderByCategoryAscNameAsc(paysId)
                : repo.findByPaysIdAndIsActiveTrueOrderByCategoryAscNameAsc(paysId);
        }
        return templates.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DocumentTemplateDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    public DocumentTemplateDto create(SaveDocumentTemplateDto dto, Long actorId) {
        if (repo.existsByPaysIdAndName(dto.getPaysId(), dto.getName())) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Une maquette nommée \"" + dto.getName() + "\" existe déjà pour ce pays.");
        }
        DocumentTemplate tmpl = DocumentTemplate.builder()
            .paysId(dto.getPaysId())
            .category(dto.getCategory())
            .name(dto.getName().trim())
            .description(dto.getDescription())
            .htmlContent(dto.getHtmlContent())
            .variables(extractVariablesJson(dto.getHtmlContent()))
            .pageSize(dto.getPageSize() != null ? dto.getPageSize() : "A4")
            .isActive(true)
            .createdBy(actorId)
            .createdAt(OffsetDateTime.now())
            .build();
        return toDto(repo.save(tmpl));
    }

    public DocumentTemplateDto update(Long id, SaveDocumentTemplateDto dto) {
        DocumentTemplate tmpl = findOrThrow(id);
        if (repo.existsByPaysIdAndNameAndIdNot(tmpl.getPaysId(), dto.getName(), id)) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Une maquette nommée \"" + dto.getName() + "\" existe déjà pour ce pays.");
        }
        tmpl.setCategory(dto.getCategory());
        tmpl.setName(dto.getName().trim());
        tmpl.setDescription(dto.getDescription());
        tmpl.setHtmlContent(dto.getHtmlContent());
        tmpl.setVariables(extractVariablesJson(dto.getHtmlContent()));
        tmpl.setPageSize(dto.getPageSize() != null ? dto.getPageSize() : tmpl.getPageSize());
        tmpl.setUpdatedAt(OffsetDateTime.now());
        return toDto(repo.save(tmpl));
    }

    public DocumentTemplateDto toggleActive(Long id) {
        DocumentTemplate tmpl = findOrThrow(id);
        tmpl.setIsActive(!Boolean.TRUE.equals(tmpl.getIsActive()));
        tmpl.setUpdatedAt(OffsetDateTime.now());
        return toDto(repo.save(tmpl));
    }

    public void delete(Long id) {
        DocumentTemplate tmpl = findOrThrow(id);
        tmpl.setIsActive(false);
        tmpl.setUpdatedAt(OffsetDateTime.now());
        repo.save(tmpl);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /** Admin preview — renders by template ID, uses placeholder context when profileId is null. */
    public byte[] render(Long templateId, Long employeeProfileId) {
        DocumentTemplate tmpl = findOrThrow(templateId);
        Map<String, String> ctx = resolveContext(employeeProfileId, tmpl.getPaysId());
        String resolved = replaceVariables(tmpl.getHtmlContent(), ctx);
        return pdfClient.generatePdfFromHtml(resolved, sanitizeFilename(tmpl.getName()) + ".pdf");
    }

    /** Admin raw-HTML preview — renders arbitrary HTML without saving. */
    public byte[] previewRaw(String htmlContent, Long paysId, Long employeeProfileId) {
        Map<String, String> ctx = resolveContext(employeeProfileId, paysId);
        String resolved = replaceVariables(htmlContent, ctx);
        return pdfClient.generatePdfFromHtml(resolved, "apercu.pdf");
    }

    /**
     * Production render by template name.
     * Called by PdfDocumentService with pre-generated document.ref / document.verificationCode
     * in extraCtx, so those are not overwritten by the generic resolveContext().
     * Returns Optional.empty() if no active template with that name exists for the given pays.
     */
    public Optional<byte[]> renderByName(String name, Long paysId, Long profileId,
                                          Map<String, String> extraCtx) {
        return repo.findFirstByPaysIdAndNameAndIsActiveTrue(paysId, name)
            .map(tmpl -> {
                Map<String, String> ctx = resolveContext(profileId, paysId);
                if (extraCtx != null) ctx.putAll(extraCtx);
                String resolved = replaceVariables(tmpl.getHtmlContent(), ctx);
                return pdfClient.generatePdfFromHtml(resolved, sanitizeFilename(tmpl.getName()) + ".pdf");
            });
    }

    // ── Variable catalog ──────────────────────────────────────────────────────

    public List<DocumentVariableCatalog.VariableDef> getVariableCatalog() {
        return DocumentVariableCatalog.ALL;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DocumentTemplate findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() ->
            new AppException(ErrorCode.NOT_FOUND, "Maquette introuvable: id=" + id));
    }

    private DocumentTemplateDto toDto(DocumentTemplate t) {
        List<String> vars = null;
        if (t.getVariables() != null) {
            try {
                vars = objectMapper.readValue(t.getVariables(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }
        return DocumentTemplateDto.builder()
            .id(t.getId())
            .paysId(t.getPaysId())
            .category(t.getCategory())
            .name(t.getName())
            .description(t.getDescription())
            .htmlContent(t.getHtmlContent())
            .variables(vars)
            .pageSize(t.getPageSize())
            .isActive(t.getIsActive())
            .createdAt(t.getCreatedAt())
            .updatedAt(t.getUpdatedAt())
            .build();
    }

    /** Scans HTML for {{key}} tokens and stores them as a JSON array. */
    private String extractVariablesJson(String html) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(html);
        while (m.find()) keys.add(m.group(1).trim());
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String replaceVariables(String html, Map<String, String> ctx) {
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return html;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_àâäéèêëîïôùûüç ]", "")
                   .trim().replace(' ', '_');
    }

    /**
     * Builds the full variable context map for a given employee profile and pays.
     * When profileId is null, every employee variable gets a labeled placeholder for preview.
     * document.ref and document.verificationCode default to "PREVIEW" — callers that need
     * real values must override them via the extraCtx parameter of renderByName().
     */
    private Map<String, String> resolveContext(Long profileId, Long paysId) {
        Map<String, String> ctx = new HashMap<>();

        // ── Date ──────────────────────────────────────────────────────────────
        LocalDate today = LocalDate.now();
        ctx.put("date.today",      today.format(DATE_FR));
        ctx.put("date.day",        String.valueOf(today.getDayOfMonth()));
        ctx.put("date.month",      String.valueOf(today.getMonthValue()));
        ctx.put("date.monthLabel", MONTH_LABELS_FR[today.getMonthValue() - 1]);
        ctx.put("date.year",       String.valueOf(today.getYear()));

        // ── Document defaults (overridden by extraCtx in production renders) ──
        ctx.put("document.date",             formatDateLongFr(today));
        ctx.put("document.ref",              "PREVIEW");
        ctx.put("document.verificationCode", "PREVIEW");

        // ── Company / DG parameters ───────────────────────────────────────────
        if (paysId != null) {
            try {
                List<Map<String, Object>> dgRows = jdbc.queryForList(DG_CTX_SQL, paysId);
                for (Map<String, Object> row : dgRows) {
                    String cle    = (String) row.get("cle");
                    String valeur = (String) row.get("valeur");
                    switch (cle) {
                        case "DG_NAME"      -> ctx.put("company.dgName",    valeur);
                        case "DG_CIN"       -> ctx.put("company.dgCin",     valeur);
                        case "DG_CIN_DATE"  -> ctx.put("company.dgCinDate", valeur);
                        case "DG_CIN_CITY"  -> ctx.put("company.dgCinCity", valeur);
                        case "DG_TITLE"     -> ctx.put("company.dgTitle",   valeur);
                        case "COMPANY_NAME" -> ctx.put("company.name",      valeur);
                    }
                }
            } catch (Exception ex) {
                log.warn("Could not load DG parameters for paysId={}: {}", paysId, ex.getMessage());
            }
        }

        // ── Employee data ─────────────────────────────────────────────────────
        if (profileId == null) {
            ctx.put("employee.fullName",                   "[NOM COMPLET]");
            ctx.put("employee.firstName",                  "[PRÉNOM]");
            ctx.put("employee.lastName",                   "[NOM]");
            ctx.put("employee.civilite",                   "M.");
            ctx.put("employee.cin",                        "[N° CIN]");
            ctx.put("employee.cinCity",                    "[VILLE CIN]");
            ctx.put("employee.cinDate",                    "[DATE CIN]");
            ctx.put("employee.grade",                      "[GRADE]");
            ctx.put("employee.position",                   "[POSTE]");
            ctx.put("employee.startDate",                  "[DATE EMBAUCHE]");
            ctx.put("employee.startDateMoisAn",            "[MOIS EMBAUCHE]");
            ctx.put("employee.titularisationDate",         "[DATE TITULARISATION]");
            ctx.put("employee.contractType",               "[TYPE CONTRAT]");
            ctx.put("employee.contractDuration",           "[DURÉE CONTRAT]");
            ctx.put("employee.salary",                     "[SALAIRE NET]");
            ctx.put("employee.salaireBrutAnnuel",          "[SALAIRE BRUT ANNUEL]");
            ctx.put("employee.salaireBrutAnnuelEnLettres", "[SALAIRE EN LETTRES]");
            ctx.put("employee.bank",                       "[BANQUE]");
            ctx.put("employee.rib",                        "[RIB]");
            ctx.put("employee.iban",                       "[IBAN]");
            ctx.put("employee.city",                       "[VILLE]");
            ctx.put("employee.email",                      "[EMAIL]");
            ctx.putIfAbsent("company.name",                "[ENTREPRISE]");
            return ctx;
        }

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(EMPLOYEE_CTX_SQL, profileId);
            if (rows.isEmpty()) {
                log.warn("No employee data found for profileId={}", profileId);
                return ctx;
            }
            Map<String, Object> r = rows.get(0);

            String firstName  = (String) r.get("first_name");
            String lastName   = (String) r.get("last_name");
            String fullName   = (String) r.get("full_name");
            String gender     = (String) r.get("gender");
            String grade      = (String) r.get("grade");
            String discipline = (String) r.get("discipline");
            String isoCode    = (String) r.get("iso_code");
            String paysLabel  = (String) r.get("pays_label");

            ctx.put("employee.fullName",  fullName != null ? fullName : (firstName + " " + lastName));
            ctx.put("employee.firstName", nvl(firstName));
            ctx.put("employee.lastName",  nvl(lastName));
            ctx.put("employee.civilite",  "FEMALE".equalsIgnoreCase(gender) ? "Mme" : "M.");
            ctx.put("employee.cin",       nvl((String) r.get("national_id")));
            ctx.put("employee.cinCity",   nvl((String) r.get("cin_city")));
            ctx.put("employee.cinDate",   nvl((String) r.get("cin_date")));
            ctx.put("employee.grade",     nvl(grade));
            ctx.put("employee.position",  (grade != null && !grade.isEmpty()) ? grade : nvl(discipline));
            ctx.put("employee.email",     nvl((String) r.get("ms365_email")));
            ctx.put("employee.city",      deriveCity(isoCode, paysLabel));
            ctx.putIfAbsent("company.name", nvl(paysLabel));

            // Hire date — two formats
            Object hireDateObj = r.get("hire_date");
            if (hireDateObj instanceof java.sql.Date sd) {
                LocalDate hd = sd.toLocalDate();
                ctx.put("employee.startDate",       formatDateLongFr(hd));
                ctx.put("employee.startDateMoisAn", formatMoisAnFr(hd));
            } else {
                ctx.put("employee.startDate",       "________");
                ctx.put("employee.startDateMoisAn", "________");
            }

            // Titularisation date (probation end date, fallback to hire date)
            Object probEndObj = r.get("probation_end_date");
            if (probEndObj instanceof java.sql.Date pd) {
                ctx.put("employee.titularisationDate", formatDateLongFr(pd.toLocalDate()));
            } else if (hireDateObj instanceof java.sql.Date sd) {
                ctx.put("employee.titularisationDate", formatDateLongFr(sd.toLocalDate()));
            } else {
                ctx.put("employee.titularisationDate", "________");
            }

            // Contract
            String contractType = (String) r.get("contract_type");
            ctx.put("employee.contractType",     nvl(contractType));
            ctx.put("employee.contractDuration", deriveContractDuration(contractType));

            // Salary
            Object salaryObj = r.get("salaire_net_rh");
            if (salaryObj instanceof BigDecimal net) {
                ctx.put("employee.salary", formatAmount(net));
                BigDecimal annuel = net.multiply(BigDecimal.valueOf(12));
                ctx.put("employee.salaireBrutAnnuel",          formatAmount(annuel));
                ctx.put("employee.salaireBrutAnnuelEnLettres", NumberToWordsFr.convert(annuel));
            } else {
                ctx.put("employee.salary",                     "0");
                ctx.put("employee.salaireBrutAnnuel",          "0");
                ctx.put("employee.salaireBrutAnnuelEnLettres", "zéro");
            }

            // Bank
            ctx.put("employee.bank", nvl((String) r.get("bank_name")));
            ctx.put("employee.rib",  nvl((String) r.get("rib")));
            ctx.put("employee.iban", nvl((String) r.get("iban")));

        } catch (Exception ex) {
            log.warn("Could not resolve employee context for profileId={}: {}", profileId, ex.getMessage());
        }

        return ctx;
    }

    // ── Format helpers ────────────────────────────────────────────────────────

    /** "15 janvier 2024" — no accent in month, matches PdfDocumentService.formatDateFr(). */
    private String formatDateLongFr(LocalDate d) {
        if (d == null) return "________";
        return d.getDayOfMonth() + " " + MOIS_FR[d.getMonthValue() - 1] + " " + d.getYear();
    }

    /** "janvier 2024" — matches PdfDocumentService.formatMoisAnFr(). */
    private String formatMoisAnFr(LocalDate d) {
        if (d == null) return "________";
        return MOIS_FR[d.getMonthValue() - 1] + " " + d.getYear();
    }

    private String deriveCity(String iso, String label) {
        if (iso == null) return label != null ? label : "________";
        return switch (iso.toUpperCase()) {
            case "TN" -> "Tunis";
            case "EG" -> "Le Caire";
            default   -> label != null ? label : "________";
        };
    }

    private String deriveContractDuration(String ct) {
        if (ct == null) return "indeterminee";
        return switch (ct.toUpperCase()) {
            case "PERMANENT"  -> "indeterminee (titulaire)";
            case "FIXED_TERM" -> "determinee";
            case "INTERN"     -> "stage";
            default           -> "indeterminee";
        };
    }

    private String formatAmount(BigDecimal v) {
        if (v == null) return "0";
        long whole = v.longValue();
        String wholeStr = String.format("%,d", whole).replace(',', ' ');
        int cents = v.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).intValue();
        if (cents == 0) return wholeStr;
        return wholeStr + "," + String.format("%02d", cents);
    }

    private String nvl(String s) {
        return s != null ? s : "________";
    }
}
