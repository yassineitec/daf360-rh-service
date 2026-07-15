package com.daf360.rh.service.pdf;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.dto.pdf.DgParametersDto;
import com.daf360.rh.dto.pdf.EmployeeDataDto;
import com.daf360.rh.dto.pdf.GeneratedDocumentResponse;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PdfDocumentService {

    private final PdfClientService pdfClient;
    private final JdbcTemplate     jdbc;
    private final AppProperties    appProperties;

    // -------------------------------------------------------------------------
    // SQL constants
    // -------------------------------------------------------------------------

    private static final String EMPLOYEE_SQL =
            "SELECT ep.id as ep_id, ep.user_id, ep.pays_id, ep.candidate_id, " +
            "ep.gender, ep.national_id, ep.cin_city, ep.cin_date, " +
            "ISNULL(g.label_fr,  '') AS grade, " +
            "ISNULL(d.label_fr,  '') AS discipline, " +
            "ep.contract_type, " +
            "ep.hire_date, ep.probation_end_date, ep.contract_end_date, " +
            "ISNULL(b.label_fr, '') AS bank_name, ep.rib, ep.iban, " +
            "u.fullName, u.username as ms365_email, " +
            "p.iso_code, p.french_label as pays_label " +
            "FROM employee_profiles ep " +
            "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
            "JOIN [dbo].[pays]  p ON p.id = ep.pays_id " +
            "LEFT JOIN [dbo].[grades]      g ON g.id = ep.grade_id " +
            "LEFT JOIN [dbo].[disciplines] d ON d.id = ep.discipline_id " +
            "LEFT JOIN [dbo].[banks]       b ON b.id = ep.bank_id " +
            "WHERE ep.id = ?";

    private static final String DG_SQL =
            "SELECT cle, valeur FROM parameter_sets " +
            "WHERE pays_id = ? AND cle IN ('DG_NAME','DG_CIN','DG_CIN_DATE','DG_CIN_CITY','DG_TITLE')";

    private static final String SALARY_SQL =
            "SELECT salaire_net_rh FROM [dbo].[employee_profiles] WHERE id = ?";

    // V23: hardware columns moved to it_assets — pivot them back for the PDF template
    private static final String PROV_SQL =
            "SELECT " +
            "  MAX(CASE WHEN iat.code='LAPTOP'          THEN CAST(ia.provided AS INT) ELSE 0 END) AS laptop_provided, " +
            "  MAX(CASE WHEN iat.code='MOUSE'           THEN CAST(ia.provided AS INT) ELSE 0 END) AS mouse_provided, " +
            "  MAX(CASE WHEN iat.code='KEYBOARD'        THEN CAST(ia.provided AS INT) ELSE 0 END) AS keyboard_provided, " +
            "  MAX(CASE WHEN iat.code='SCREEN'          THEN CAST(ia.provided AS INT) ELSE 0 END) AS screen_provided, " +
            "  MAX(CASE WHEN iat.code='HEADSET'         THEN CAST(ia.provided AS INT) ELSE 0 END) AS headset_provided, " +
            "  MAX(CASE WHEN iat.code='DOCKING_STATION' THEN CAST(ia.provided AS INT) ELSE 0 END) AS docking_station_provided, " +
            "  MAX(CASE WHEN iat.code='LAPTOP' THEN ia.serial_number END) AS laptop_sn, " +
            "  MAX(CASE WHEN iat.code='LAPTOP' THEN ia.brand_model   END) AS laptop_brand, " +
            "  MAX(CASE WHEN iat.code='SCREEN' THEN ia.serial_number END) AS screen_sn, " +
            "  MAX(CASE WHEN iat.code='SCREEN' THEN ia.brand_model   END) AS screen_brand, " +
            "  ip.license_other " +
            "FROM [dbo].[it_provisioning] ip " +
            "LEFT JOIN [dbo].[it_assets]      ia  ON ia.provisioning_id = ip.id " +
            "LEFT JOIN [dbo].[it_asset_types] iat ON iat.id = ia.asset_type_id " +
            "WHERE ip.id = ? " +
            "GROUP BY ip.id, ip.license_other";

    private static final String CAND_SQL =
            "SELECT first_name, last_name, gender, national_id, pays_id, cin_city, cin_date " +
            "FROM candidates WHERE id = ?";

    private static final String PAYS_SQL =
            "SELECT french_label, iso_code FROM pays WHERE id = ?";

    // -------------------------------------------------------------------------
    // Public methods
    // -------------------------------------------------------------------------

    // REQUIRES_NEW: runs in its own transaction so a PDF failure never rolls back the caller
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public GeneratedDocumentResponse generateDechargePdf(Long candidateId,
                                                         Long itProvisioningId,
                                                         String context,
                                                         Long generatedBy) {
        Map<String, Object> cand = jdbc.queryForList(CAND_SQL, candidateId)
                .stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));

        Map<String, Object> prov = jdbc.queryForList(PROV_SQL, itProvisioningId)
                .stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.IT_PROVISIONING_NOT_FOUND));

        Long paysId = toLong(cand.get("pays_id"));
        Map<String, Object> pays = jdbc.queryForList(PAYS_SQL, paysId)
                .stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.IT_PAYS_NOT_FOUND));

        DgParametersDto dg = loadDgParameters(paysId);

        String firstName  = (String) cand.get("first_name");
        String lastName   = (String) cand.get("last_name");
        String fullName   = firstName + " " + lastName;
        String gender     = (String) cand.get("gender");
        String nationalId = (String) cand.get("national_id");
        String cinCity    = (String) cand.get("cin_city");
        String cinDate    = cand.get("cin_date") != null ? cand.get("cin_date").toString() : "________";
        String isoCode    = (String) pays.get("iso_code");
        String paysLabel  = (String) pays.get("french_label");

        String verCode = generateVerificationCode();

        Map<String, Object> data = new HashMap<>();
        // DG info
        data.put("dgName",    dg.getDgName());
        data.put("dgCin",     dg.getDgCin());
        data.put("dgCinCity", dg.getDgCinCity());
        data.put("dgCinDate", dg.getDgCinDate());
        data.put("dgTitle",   dg.getDgTitle());
        // Employee identity
        data.put("civilite",   deriveCivilite(gender));
        data.put("fullName",   fullName);
        data.put("nationalId", nationalId);
        data.put("cinCity",    cinCity != null ? cinCity : "________");
        data.put("cinDate",    cinDate);
        // Hardware
        data.put("laptop_provided",          Integer.valueOf(1).equals(prov.get("laptop_provided")));
        data.put("mouse_provided",           Integer.valueOf(1).equals(prov.get("mouse_provided")));
        data.put("keyboard_provided",        Integer.valueOf(1).equals(prov.get("keyboard_provided")));
        data.put("screen_provided",          Integer.valueOf(1).equals(prov.get("screen_provided")));
        data.put("headset_provided",         Integer.valueOf(1).equals(prov.get("headset_provided")));
        data.put("docking_station_provided", Integer.valueOf(1).equals(prov.get("docking_station_provided")));
        data.put("laptopBrand",   prov.getOrDefault("laptop_brand",  ""));
        data.put("laptopSn",      prov.getOrDefault("laptop_sn",     ""));
        data.put("screenBrand",   prov.getOrDefault("screen_brand",  ""));
        data.put("screenSn",      prov.getOrDefault("screen_sn",     ""));
        data.put("license_other", prov.getOrDefault("license_other", ""));
        // Meta
        data.put("city",              deriveCity(isoCode, paysLabel));
        data.put("date",              formatDateFr(LocalDate.now()));
        data.put("verificationCode",  verCode);
        data.put("documentRef",       "DECHARGE-" + Year.now().getValue());
        data.put("entityAddress",     "");
        data.put("context",           context != null ? context : "");

        byte[] bytes = pdfClient.generatePdf("decharge-responsabilite", data);
        return saveGeneratedDocument(null, "DECHARGE_RESPONSABILITE", bytes, verCode, generatedBy, fullName);
    }

    public GeneratedDocumentResponse generateAttestationTravailPdf(Long employeeProfileId,
                                                                    Long requestId,
                                                                    Long generatedBy) {
        EmployeeDataDto emp = loadEmployeeData(employeeProfileId);
        DgParametersDto dg  = loadDgParameters(emp.getPaysId());
        String docRef       = generateDocumentRef("RH-ATT-115", emp.getPaysId());
        String verCode      = generateVerificationCode();

        Map<String, Object> data = buildCommonData(emp, dg, docRef, verCode);
        data.put("jobTitle",          emp.getGrade() != null ? emp.getGrade() : emp.getDiscipline());
        data.put("contractDuration",  deriveContractDuration(emp.getContractType()));
        data.put("hireDate",          formatMoisAnFr(emp.getHireDate()));

        byte[] bytes = pdfClient.generatePdf("attestation-travail", data);
        return saveGeneratedDocument(requestId, "ATTESTATION_TRAVAIL", bytes, verCode, generatedBy, emp.getFullName());
    }

    public GeneratedDocumentResponse generateAttestationSalairePdf(Long employeeProfileId,
                                                                    Long requestId,
                                                                    Long generatedBy) {
        EmployeeDataDto emp = loadEmployeeData(employeeProfileId);

        // NEW: read salaire_net_rh directly from employee_profiles
        List<Map<String, Object>> salaryRows = jdbc.queryForList(SALARY_SQL, employeeProfileId);
        BigDecimal salaireNet = salaryRows.isEmpty() || salaryRows.get(0).get("salaire_net_rh") == null
                ? BigDecimal.ZERO
                : (BigDecimal) salaryRows.get(0).get("salaire_net_rh");
        BigDecimal salaireNetAnnuel = salaireNet.multiply(BigDecimal.valueOf(12));

        DgParametersDto dg = loadDgParameters(emp.getPaysId());
        String docRef      = generateDocumentRef("RH-ATT-SAL", emp.getPaysId());
        String verCode     = generateVerificationCode();

        Map<String, Object> data = buildCommonData(emp, dg, docRef, verCode);
        data.put("jobTitle",                    emp.getGrade() != null ? emp.getGrade() : emp.getDiscipline());
        data.put("contractDuration",            deriveContractDuration(emp.getContractType()));
        data.put("hireDate",                    formatMoisAnFr(emp.getHireDate()));
        data.put("salaireBrutAnnuel",           formatAmount(salaireNetAnnuel));
        data.put("salaireBrutAnnuelEnLettres",  NumberToWordsFr.convert(salaireNetAnnuel));

        byte[] bytes = pdfClient.generatePdf("attestation-salaire", data);
        return saveGeneratedDocument(requestId, "ATTESTATION_SALAIRE", bytes, verCode, generatedBy, emp.getFullName());
    }

    public GeneratedDocumentResponse generateAttestationNonBeneficePretPdf(Long employeeProfileId,
                                                                            Long requestId,
                                                                            Long generatedBy) {
        EmployeeDataDto emp = loadEmployeeData(employeeProfileId);
        DgParametersDto dg  = loadDgParameters(emp.getPaysId());
        String docRef       = generateDocumentRef("RH-ATT-PRET", emp.getPaysId());
        String verCode      = generateVerificationCode();

        Map<String, Object> data = buildCommonData(emp, dg, docRef, verCode);

        byte[] bytes = pdfClient.generatePdf("attestation-non-benefice-pret", data);
        return saveGeneratedDocument(requestId, "ATTESTATION_NON_BENEFICE_PRET", bytes, verCode, generatedBy, emp.getFullName());
    }

    public GeneratedDocumentResponse generateAttestationTitularisationPdf(Long employeeProfileId,
                                                                           Long requestId,
                                                                           Long generatedBy) {
        EmployeeDataDto emp = loadEmployeeData(employeeProfileId);

        if (emp.getContractType() == null || !"PERMANENT".equalsIgnoreCase(emp.getContractType())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "L'attestation de titularisation est reservee aux employes en CDI.");
        }

        LocalDate titularisationDate = emp.getProbationEndDate() != null
                ? emp.getProbationEndDate()
                : emp.getHireDate();

        DgParametersDto dg = loadDgParameters(emp.getPaysId());
        String docRef      = generateDocumentRef("RH-ATT-TIT", emp.getPaysId());
        String verCode     = generateVerificationCode();

        Map<String, Object> data = buildCommonData(emp, dg, docRef, verCode);
        data.put("jobTitle",             emp.getGrade() != null ? emp.getGrade() : emp.getDiscipline());
        data.put("titularisationDate",   formatDateFr(titularisationDate));

        byte[] bytes = pdfClient.generatePdf("attestation-titularisation", data);
        return saveGeneratedDocument(requestId, "ATTESTATION_TITULARISATION", bytes, verCode, generatedBy, emp.getFullName());
    }

    public GeneratedDocumentResponse generateAttestationDomiciliationSalairePdf(Long employeeProfileId,
                                                                                 Long requestId,
                                                                                 Long generatedBy) {
        EmployeeDataDto emp = loadEmployeeData(employeeProfileId);

        if (emp.getBankName() == null || emp.getRib() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Les coordonnees bancaires de " + emp.getFullName() +
                    " ne sont pas renseignees. Veuillez les completer dans son profil.");
        }

        DgParametersDto dg = loadDgParameters(emp.getPaysId());
        String docRef      = generateDocumentRef("RH-ATT-DOM", emp.getPaysId());
        String verCode     = generateVerificationCode();

        Map<String, Object> data = buildCommonData(emp, dg, docRef, verCode);
        data.put("jobTitle",          emp.getGrade() != null ? emp.getGrade() : emp.getDiscipline());
        data.put("hireDate",          formatDateFr(emp.getHireDate()));
        data.put("bankName",          emp.getBankName());
        data.put("rib",               emp.getRib());
        data.put("iban",              emp.getIban() != null ? emp.getIban() : "--");

        byte[] bytes = pdfClient.generatePdf("attestation-domiciliation-salaire", data);
        return saveGeneratedDocument(requestId, "ATTESTATION_DOMICILIATION_SALAIRE", bytes, verCode, generatedBy, emp.getFullName());
    }

    @Transactional(readOnly = true)
    public List<GeneratedDocumentResponse> listByProfile(Long employeeProfileId) {
        String sql =
                "SELECT gd.* FROM generated_documents gd " +
                "WHERE gd.employee_request_id IN (" +
                "  SELECT id FROM employee_requests " +
                "  WHERE user_id = (SELECT user_id FROM employee_profiles WHERE id = ?)" +
                ") ORDER BY gd.generated_at DESC";
        return jdbc.query(sql, (rs, rowNum) -> mapDoc(rs), employeeProfileId);
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedDocumentResponse> findByRequest(Long requestId) {
        List<GeneratedDocumentResponse> rows = jdbc.query(
                "SELECT * FROM generated_documents WHERE employee_request_id = ?",
                (rs, rowNum) -> mapDoc(rs),
                requestId);
        return rows.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public byte[] downloadDocument(Long documentId) {
        String fileUrl = jdbc.queryForList(
                "SELECT file_url FROM generated_documents WHERE id = ?", documentId)
                .stream().findFirst()
                .map(r -> (String) r.get("file_url"))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Document introuvable: " + documentId));

        try {
            return Files.readAllBytes(Path.of(fileUrl));
        } catch (java.io.IOException e) {
            log.error("Cannot read PDF file {}: {}", fileUrl, e.getMessage());
            throw new PdfGenerationException("Impossible de lire le fichier PDF: " + fileUrl, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private EmployeeDataDto loadEmployeeData(Long empProfileId) {
        try {
            return jdbc.queryForObject(EMPLOYEE_SQL, (rs, rn) -> EmployeeDataDto.builder()
                    .employeeProfileId(rs.getLong("ep_id"))
                    .userId(rs.getLong("user_id"))
                    .paysId(rs.getLong("pays_id"))
                    .candidateId(rs.getObject("candidate_id") != null ? rs.getLong("candidate_id") : null)
                    .fullName(rs.getString("fullName"))
                    .ms365Email(rs.getString("ms365_email"))
                    .isoCode(rs.getString("iso_code"))
                    .paysLabel(rs.getString("pays_label"))
                    .gender(rs.getString("gender"))
                    .nationalId(rs.getString("national_id"))
                    .cinCity(rs.getString("cin_city"))
                    .cinDate(rs.getString("cin_date"))
                    .grade(rs.getString("grade"))
                    .discipline(rs.getString("discipline"))
                    .contractType(rs.getString("contract_type"))
                    .hireDate(toLocalDate(rs.getObject("hire_date")))
                    .probationEndDate(toLocalDate(rs.getObject("probation_end_date")))
                    .contractEndDate(toLocalDate(rs.getObject("contract_end_date")))
                    .bankName(rs.getString("bank_name"))
                    .rib(rs.getString("rib"))
                    .iban(rs.getString("iban"))
                    .build(),
                    empProfileId);
        } catch (Exception e) {
            log.error("Employee profile {} not found: {}", empProfileId, e.getMessage());
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }
    }

    private DgParametersDto loadDgParameters(Long paysId) {
        Map<String, String> params = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(DG_SQL, paysId);
        for (Map<String, Object> row : rows) {
            params.put((String) row.get("cle"), (String) row.get("valeur"));
        }
        return DgParametersDto.builder()
                .dgName(params.getOrDefault("DG_NAME",     "________"))
                .dgCin(params.getOrDefault("DG_CIN",       "________"))
                .dgCinCity(params.getOrDefault("DG_CIN_CITY", "________"))
                .dgCinDate(params.getOrDefault("DG_CIN_DATE", "________"))
                .dgTitle(params.getOrDefault("DG_TITLE",   "________"))
                .build();
    }

    private Map<String, Object> buildCommonData(EmployeeDataDto emp, DgParametersDto dg,
                                                 String docRef, String verCode) {
        Map<String, Object> data = new HashMap<>();
        data.put("dgName",           dg.getDgName());
        data.put("dgCin",            dg.getDgCin());
        data.put("dgCinCity",        dg.getDgCinCity());
        data.put("dgCinDate",        dg.getDgCinDate());
        data.put("dgTitle",          dg.getDgTitle());
        data.put("civilite",         deriveCivilite(emp.getGender()));
        data.put("fullName",         emp.getFullName());
        data.put("nationalId",       emp.getNationalId() != null ? emp.getNationalId() : "________");
        data.put("cinCity",          emp.getCinCity()   != null ? emp.getCinCity()    : "________");
        data.put("cinDate",          emp.getCinDate()   != null ? emp.getCinDate()    : "________");
        data.put("city",             deriveCity(emp.getIsoCode(), emp.getPaysLabel()));
        data.put("date",             formatDateFr(LocalDate.now()));
        data.put("documentRef",      docRef);
        data.put("verificationCode", verCode);
        data.put("entityAddress",    "");
        return data;
    }

    private GeneratedDocumentResponse saveGeneratedDocument(Long requestId,
                                                             String docType,
                                                             byte[] pdfBytes,
                                                             String verCode,
                                                             Long generatedBy,
                                                             String employeeName) {
        String dir = appProperties.getStoragePath() + "/hr/documents/" + docType.toLowerCase();
        try {
            Files.createDirectories(Path.of(dir));
        } catch (java.io.IOException e) {
            throw new PdfGenerationException("Cannot create storage directory: " + dir, e);
        }

        String fileName = docType + "-" + System.currentTimeMillis() + ".pdf";
        String filePath = dir + "/" + fileName;
        try {
            Files.write(Path.of(filePath), pdfBytes);
        } catch (java.io.IOException e) {
            throw new PdfGenerationException("Cannot write PDF file: " + filePath, e);
        }

        OffsetDateTime now = OffsetDateTime.now();

        // INSERT and return generated id
        Long newId = jdbc.queryForObject(
                "INSERT INTO generated_documents " +
                "(employee_request_id, document_type, file_url, verification_code, generated_at, generated_by) " +
                "OUTPUT INSERTED.id " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                Long.class,
                requestId, docType, filePath, verCode, now, generatedBy);

        String downloadUrl = "/api/hr/documents/download/" + newId;

        return GeneratedDocumentResponse.builder()
                .id(newId)
                .employeeRequestId(requestId)
                .documentType(docType)
                .fileUrl(filePath)
                .verificationCode(verCode)
                .generatedAt(now)
                .generatedBy(generatedBy)
                .downloadUrl(downloadUrl)
                .build();
    }

    private String generateDocumentRef(String prefix, Long paysId) {
        int year = Year.now().getValue();
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generated_documents " +
                "WHERE document_type LIKE ? AND YEAR(generated_at) = ?",
                Integer.class, prefix + "%", year);
        int seq = (maxSeq != null ? maxSeq : 0);
        return prefix + "-" + year + "-" + String.format("%03d", seq + 1);
    }

    private String generateVerificationCode() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 8);
    }

    private String deriveCivilite(String gender) {
        return "FEMALE".equalsIgnoreCase(gender) ? "Mme" : "M.";
    }

    private String deriveContractDuration(String ct) {
        if (ct == null) return "indeterminee";
        return switch (ct.toUpperCase()) {
            case "PERMANENT"   -> "indeterminee (titulaire)";
            case "FIXED_TERM"  -> "determinee";
            case "INTERN"      -> "stage";
            default            -> "indeterminee";
        };
    }

    private String deriveCity(String iso, String label) {
        if (iso == null) return label != null ? label : "________";
        return switch (iso.toUpperCase()) {
            case "TN" -> "Tunis";
            case "EG" -> "Le Caire";
            default   -> label != null ? label : "________";
        };
    }

    private static final String[] MOIS_FR = {
        "janvier", "fevrier", "mars", "avril", "mai", "juin",
        "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
    };

    private String formatDateFr(LocalDate d) {
        if (d == null) return "________";
        return d.getDayOfMonth() + " " + MOIS_FR[d.getMonthValue() - 1] + " " + d.getYear();
    }

    private String formatMoisAnFr(LocalDate d) {
        if (d == null) return "________";
        return MOIS_FR[d.getMonthValue() - 1] + " " + d.getYear();
    }

    private String formatAmount(BigDecimal v) {
        if (v == null) return "0";
        // Format with space as thousands separator and comma as decimal separator
        long whole    = v.longValue();
        String wholeStr = String.format("%,d", whole).replace(',', ' ');
        int cents = v.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).intValue();
        if (cents == 0) return wholeStr;
        return wholeStr + "," + String.format("%02d", cents);
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    private LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate) return (LocalDate) v;
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate();
        return LocalDate.parse(v.toString());
    }

    private GeneratedDocumentResponse mapDoc(ResultSet rs) throws SQLException {
        return GeneratedDocumentResponse.builder()
                .id(rs.getLong("id"))
                .employeeRequestId(rs.getObject("employee_request_id") != null
                        ? rs.getLong("employee_request_id") : null)
                .documentType(rs.getString("document_type"))
                .fileUrl(rs.getString("file_url"))
                .verificationCode(rs.getString("verification_code"))
                .generatedAt(rs.getObject("generated_at", OffsetDateTime.class))
                .generatedBy(rs.getObject("generated_by") != null ? rs.getLong("generated_by") : null)
                // Relative to the HR API (rh-service), NOT the Node pdf-service.
                // The frontend prepends its hrApiUrl. (Was wrongly using
                // getPdfServiceUrl(), which pointed downloads at the PDF generator.)
                .downloadUrl("/api/hr/documents/download/" + rs.getLong("id"))
                .build();
    }
}
