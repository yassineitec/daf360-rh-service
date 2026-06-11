package com.daf360.rh.service;

import com.daf360.rh.domain.ParameterSet;
import com.daf360.rh.dto.admin.ParameterDto;
import com.daf360.rh.dto.admin.ParameterResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.ParameterSetRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages [parameter_sets] — Admin only.
 *
 * Key names (cle):
 *   TAUX_CNSS_EMPLOYE  — percentage, e.g. "9.18"
 *   TAUX_CSS           — percentage, e.g. "1.0"
 *   IRPP_BRACKETS      — JSON array of {from, to, rate}
 *   DEVISE             — ISO 4217, e.g. "TND"
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ParameterSetService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final ParameterSetRepository paramRepo;
    private final AuditService           auditService;
    private final ObjectMapper           objectMapper;
    private final JdbcTemplate           jdbcTemplate;

    // ── Inline value types (formerly in PayrollCalculationEngine) ────────────

    public record IrppBracket(BigDecimal from, BigDecimal to, BigDecimal rate) {}

    public record PayrollParams(BigDecimal tauxCnss, BigDecimal tauxCss,
                                List<IrppBracket> irppBrackets, String devise) {}

    // ── Load params ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PayrollParams loadParams(Long paysId) {
        Map<String, String> vals = paramRepo.findByPaysId(paysId).stream()
                .collect(Collectors.toMap(ParameterSet::getCle, ParameterSet::getValeur));

        BigDecimal tauxCnss = pct(vals.getOrDefault("TAUX_CNSS_EMPLOYE", "9.18"));
        BigDecimal tauxCss  = pct(vals.getOrDefault("TAUX_CSS", "1.0"));
        List<IrppBracket> brackets = parseIrppBrackets(
                vals.getOrDefault("IRPP_BRACKETS", DEFAULT_IRPP_BRACKETS_TN));
        String devise = vals.getOrDefault("DEVISE", "TND");

        return new PayrollParams(tauxCnss, tauxCss, brackets, devise);
    }

    // ── Seed defaults ─────────────────────────────────────────────────────────

    public void seedDefaults() {
        List<Long> paysIds = jdbcTemplate.queryForList(
                "SELECT id FROM [dbo].[pays] WHERE deleted = 0 OR deleted IS NULL", Long.class);

        for (Long paysId : paysIds) {
            if (paramRepo.countByPaysId(paysId) > 0) continue;

            // Look up iso_code
            String iso = jdbcTemplate.queryForObject(
                    "SELECT iso_code FROM [dbo].[pays] WHERE id = ?", String.class, paysId);

            List<SeedParam> seeds = "TN".equalsIgnoreCase(iso)
                    ? TN_DEFAULTS
                    : EG_DEFAULTS;   // EG or fallback for others

            seeds.forEach(sp -> saveIfAbsent(paysId, sp.cle(), sp.valeur(), sp.description()));
            log.info("Seeded {} payroll parameters for pays iso={} id={}", seeds.size(), iso, paysId);
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ParameterResponseDto> list(Long paysId) {
        return paramRepo.findByPaysId(paysId).stream().map(this::toDto).toList();
    }

    public ParameterResponseDto create(ParameterDto dto, Authentication auth) {
        if (paramRepo.existsByPaysIdAndCle(dto.getPaysId(), dto.getCle())) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Paramètre " + dto.getCle() + " existe pour paysId=" + dto.getPaysId());
        }
        ParameterSet entity = ParameterSet.builder()
                .paysId(dto.getPaysId())
                .cle(dto.getCle().toUpperCase())
                .valeur(dto.getValeur())
                .description(dto.getDescription())
                .updatedBy(actorLong(auth))
                .updatedAt(OffsetDateTime.now(PARIS))
                .build();
        ParameterSet saved = paramRepo.save(entity);
        auditService.log(actorId(auth), "CREATE_PARAMETER", "ParameterSet", saved.getId(), null, saved.getCle());
        return toDto(saved);
    }

    public ParameterResponseDto update(Long id, ParameterDto dto, Authentication auth) {
        ParameterSet entity = findOrThrow(id);
        String before = entity.getValeur();
        entity.setValeur(dto.getValeur());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());
        entity.setUpdatedBy(actorLong(auth));
        entity.setUpdatedAt(OffsetDateTime.now(PARIS));
        ParameterSet saved = paramRepo.save(entity);
        auditService.log(actorId(auth), "UPDATE_PARAMETER", "ParameterSet", id, before, dto.getValeur());
        return toDto(saved);
    }

    public void delete(Long id, Authentication auth) {
        ParameterSet entity = findOrThrow(id);
        auditService.log(actorId(auth), "DELETE_PARAMETER", "ParameterSet", id, entity.getCle(), null);
        paramRepo.delete(entity);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveIfAbsent(Long paysId, String cle, String valeur, String desc) {
        if (!paramRepo.existsByPaysIdAndCle(paysId, cle)) {
            paramRepo.save(ParameterSet.builder()
                    .paysId(paysId).cle(cle).valeur(valeur).description(desc)
                    .updatedAt(OffsetDateTime.now(PARIS)).build());
        }
    }

    private ParameterSet findOrThrow(Long id) {
        return paramRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.PARAMETER_NOT_FOUND, "Paramètre introuvable: id=" + id));
    }

    private BigDecimal pct(String s) {
        return new BigDecimal(s).divide(BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP);
    }

    private List<IrppBracket> parseIrppBrackets(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return raw.stream().map(m -> new IrppBracket(
                    new BigDecimal(m.get("from").toString()),
                    m.get("to") != null ? new BigDecimal(m.get("to").toString()) : null,
                    new BigDecimal(m.get("rate").toString())
            )).toList();
        } catch (JsonProcessingException e) {
            log.warn("Cannot parse IRPP_BRACKETS: {}", e.getMessage());
            return List.of();
        }
    }

    ParameterResponseDto toDto(ParameterSet p) {
        ParameterResponseDto dto = new ParameterResponseDto();
        dto.setId(p.getId());
        dto.setPaysId(p.getPaysId());
        dto.setCle(p.getCle());
        dto.setValeur(p.getValeur());
        dto.setDescription(p.getDescription());
        dto.setUpdatedBy(p.getUpdatedBy());
        dto.setUpdatedAt(p.getUpdatedAt());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }

    private Long actorLong(Authentication auth) {
        if (auth == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }

    // ── Seed data constants ───────────────────────────────────────────────────

    /** Tunisia (TN) IRPP annual brackets — 2024 fiscal year */
    static final String DEFAULT_IRPP_BRACKETS_TN =
            "[{\"from\":0,\"to\":5000,\"rate\":0}," +
             "{\"from\":5000,\"to\":10000,\"rate\":26}," +
             "{\"from\":10000,\"to\":20000,\"rate\":28}," +
             "{\"from\":20000,\"to\":30000,\"rate\":32}," +
             "{\"from\":30000,\"to\":50000,\"rate\":34}," +
             "{\"from\":50000,\"rate\":35}]";

    /** Egypt (EG) IRPP annual brackets — placeholder 2024 */
    static final String DEFAULT_IRPP_BRACKETS_EG =
            "[{\"from\":0,\"to\":15000,\"rate\":0}," +
             "{\"from\":15000,\"to\":30000,\"rate\":10}," +
             "{\"from\":30000,\"to\":45000,\"rate\":15}," +
             "{\"from\":45000,\"to\":60000,\"rate\":20}," +
             "{\"from\":60000,\"to\":200000,\"rate\":22.5}," +
             "{\"from\":200000,\"rate\":25}]";

    record SeedParam(String cle, String valeur, String description) {}

    static final List<SeedParam> TN_DEFAULTS = List.of(
        new SeedParam("TAUX_CNSS_EMPLOYE",  "9.18",                     "Cotisation CNSS salarié (%)"),
        new SeedParam("TAUX_CNSS_PATRONAL", "16.57",                    "Cotisation CNSS patronale (%)"),
        new SeedParam("TAUX_CSS",           "1.0",                      "Contribution Sociale de Solidarité (%)"),
        new SeedParam("TAUX_RAMT",          "0.5",                      "Régime AT/MP salarié (%)"),
        new SeedParam("IRPP_BRACKETS",      DEFAULT_IRPP_BRACKETS_TN,   "Tranches IRPP annuelles (JSON)"),
        new SeedParam("DEVISE",             "TND",                      "Devise ISO 4217"),
        new SeedParam("NB_MOIS_PAIE",       "12",                       "Nombre de mois de paie par an")
    );

    static final List<SeedParam> EG_DEFAULTS = List.of(
        new SeedParam("TAUX_CNSS_EMPLOYE",  "11.0",                     "Social Insurance employee (%)"),
        new SeedParam("TAUX_CNSS_PATRONAL", "18.75",                    "Social Insurance employer (%)"),
        new SeedParam("TAUX_CSS",           "0.0",                      "No solidarity tax (placeholder)"),
        new SeedParam("IRPP_BRACKETS",      DEFAULT_IRPP_BRACKETS_EG,   "Income Tax annual brackets (JSON)"),
        new SeedParam("DEVISE",             "EGP",                      "Egyptian Pound ISO 4217"),
        new SeedParam("NB_MOIS_PAIE",       "12",                       "Months per year")
    );
}
