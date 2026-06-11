package com.daf360.rh.service;

import com.daf360.rh.domain.ParametrageHSPays;
import com.daf360.rh.dto.overtime.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.ParametrageHSPaysRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ParametrageHSService {

    private final ParametrageHSPaysRepository repo;
    private final JdbcTemplate                jdbc;

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ParametrageHSDto> getAllActive() {
        return repo.findByActifTrueOrderByPaysId()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ParametrageHSDto> getForPays(Long paysId) {
        return repo.findByPaysIdOrderByDateCreationDesc(paysId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ParametrageHSDto getActive(Long paysId) {
        return repo.findByPaysIdAndActifTrue(paysId)
                .map(this::toDto)
                .orElse(null);
    }

    public ParametrageHSDto create(CreateParametrageHSRequest req, Long actorId) {
        validateTypeCalcul(req.getTypeCalculHs());

        // Deactivate any existing active rule for this country
        repo.findByPaysIdAndActifTrue(req.getPaysId()).ifPresent(existing -> {
            existing.setActif(false);
            existing.setDateModification(java.time.OffsetDateTime.now());
            repo.save(existing);
        });

        ParametrageHSPays p = ParametrageHSPays.builder()
                .paysId(req.getPaysId())
                .typeCalculHs(req.getTypeCalculHs())
                .heureDebutTravail(req.getHeureDebutTravail())
                .heureFinTravail(req.getHeureFinTravail())
                .jourDebutSemaine(req.getJourDebutSemaine())
                .jourFinSemaine(req.getJourFinSemaine())
                .actif(true)
                .createdBy(actorId)
                .build();
        return toDto(repo.save(p));
    }

    public ParametrageHSDto update(Long id, CreateParametrageHSRequest req) {
        ParametrageHSPays p = repo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Règle HS introuvable : " + id));
        validateTypeCalcul(req.getTypeCalculHs());
        p.setTypeCalculHs(req.getTypeCalculHs());
        p.setHeureDebutTravail(req.getHeureDebutTravail());
        p.setHeureFinTravail(req.getHeureFinTravail());
        p.setJourDebutSemaine(req.getJourDebutSemaine());
        p.setJourFinSemaine(req.getJourFinSemaine());
        p.setDateModification(java.time.OffsetDateTime.now());
        return toDto(repo.save(p));
    }

    public void deactivate(Long id) {
        repo.findById(id).ifPresent(p -> {
            p.setActif(false);
            p.setDateModification(java.time.OffsetDateTime.now());
            repo.save(p);
        });
    }

    // ── Calculation engine ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OvertimeCalculationResult calculate(OvertimeCalculationRequest req) {
        ParametrageHSPays config = repo.findByPaysIdAndActifTrue(req.getPaysId())
                .orElse(null);

        if (config == null) {
            return OvertimeCalculationResult.builder()
                    .overtimeHours(BigDecimal.ZERO)
                    .normalHours(req.getGrossHours())
                    .ruleApplied("NONE")
                    .explanation("Aucune configuration HS active pour ce pays.")
                    .isWeekendDay(false)
                    .build();
        }

        // Load entity weekend days for this country
        Set<DayOfWeek> weekendDays = loadWeekendDays(req.getPaysId());
        boolean isWeekend = weekendDays.contains(req.getWorkDate().getDayOfWeek());

        String paysIso = fetchPaysIso(req.getPaysId());
        BigDecimal overtime = BigDecimal.ZERO;
        String explanation;

        switch (config.getTypeCalculHs()) {

            case "WEEKEND_ONLY":
                if (isWeekend) {
                    overtime = req.getGrossHours();
                    explanation = "Toutes les heures sont HS car " + req.getWorkDate().getDayOfWeek()
                            + " est un jour de repos (" + paysIso + ").";
                } else {
                    overtime = BigDecimal.ZERO;
                    explanation = "Jour ouvré — aucune HS (règle WEEKEND_ONLY).";
                }
                break;

            case "AFTER_WORK_HOURS":
                overtime = computeAfterWorkHours(req.getWorkStartTime(), req.getWorkEndTime(),
                        config.getHeureDebutTravail(), config.getHeureFinTravail());
                explanation = buildAfterWorkExplanation(req.getWorkStartTime(), req.getWorkEndTime(),
                        config.getHeureDebutTravail(), config.getHeureFinTravail(), overtime);
                break;

            case "MIXTE":
                if (isWeekend) {
                    overtime = req.getGrossHours();
                    explanation = "MIXTE — jour de repos : toutes les heures comptent comme HS.";
                } else {
                    overtime = computeAfterWorkHours(req.getWorkStartTime(), req.getWorkEndTime(),
                            config.getHeureDebutTravail(), config.getHeureFinTravail());
                    explanation = "MIXTE — jour ouvré : " + buildAfterWorkExplanation(
                            req.getWorkStartTime(), req.getWorkEndTime(),
                            config.getHeureDebutTravail(), config.getHeureFinTravail(), overtime);
                }
                break;

            default:
                overtime = BigDecimal.ZERO;
                explanation = "Type de calcul inconnu : " + config.getTypeCalculHs();
        }

        BigDecimal normalHours = req.getGrossHours().subtract(overtime).max(BigDecimal.ZERO);

        return OvertimeCalculationResult.builder()
                .overtimeHours(overtime.setScale(2, RoundingMode.HALF_UP))
                .normalHours(normalHours.setScale(2, RoundingMode.HALF_UP))
                .ruleApplied(config.getTypeCalculHs())
                .explanation(explanation)
                .isWeekendDay(isWeekend)
                .paysIsoCode(paysIso)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BigDecimal computeAfterWorkHours(LocalTime workStart, LocalTime workEnd,
                                              LocalTime normalStart, LocalTime normalEnd) {
        if (workStart == null || workEnd == null) return BigDecimal.ZERO;

        long overtimeMinutes = 0;

        // Hours before normal start (early start)
        if (normalStart != null && workStart.isBefore(normalStart)) {
            overtimeMinutes += ChronoUnit.MINUTES.between(workStart, normalStart);
        }

        // Hours after normal end (late finish)
        if (normalEnd != null && workEnd.isAfter(normalEnd)) {
            overtimeMinutes += ChronoUnit.MINUTES.between(normalEnd, workEnd);
        }

        return BigDecimal.valueOf(overtimeMinutes).divide(SIXTY, 4, RoundingMode.HALF_UP);
    }

    private String buildAfterWorkExplanation(LocalTime ws, LocalTime we,
                                              LocalTime ns, LocalTime ne,
                                              BigDecimal ot) {
        if (ot.compareTo(BigDecimal.ZERO) == 0)
            return "Aucune HS — travail dans les horaires normaux (" + ns + "–" + ne + ").";
        return String.format("%.2fh HS (travail %s–%s, horaires normaux %s–%s).",
                ot, ws, we, ns, ne);
    }

    private Set<DayOfWeek> loadWeekendDays(Long paysId) {
        Set<DayOfWeek> days = new HashSet<>();
        try {
            List<String> raw = jdbc.queryForList(
                    "SELECT [day] FROM [dbo].[pays_weekends] WHERE pays_id=?", String.class, paysId);
            for (String d : raw) {
                DayOfWeek dow = parseDayVarchar(d);
                if (dow != null) days.add(dow);
            }
        } catch (Exception e) {
            log.warn("Cannot load weekend days for paysId={}: {}", paysId, e.getMessage());
            days.add(DayOfWeek.SATURDAY); days.add(DayOfWeek.SUNDAY);
        }
        if (days.isEmpty()) { days.add(DayOfWeek.SATURDAY); days.add(DayOfWeek.SUNDAY); }
        return days;
    }

    private DayOfWeek parseDayVarchar(String day) {
        if (day == null) return null;
        return switch (day.toUpperCase().trim()) {
            case "MONDAY"    -> DayOfWeek.MONDAY;
            case "TUESDAY"   -> DayOfWeek.TUESDAY;
            case "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THURSDAY"  -> DayOfWeek.THURSDAY;
            case "FRIDAY"    -> DayOfWeek.FRIDAY;
            case "SATURDAY"  -> DayOfWeek.SATURDAY;
            case "SUNDAY"    -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Returns all active countries for the dropdown in admin UI */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> getAllPays() {
        return jdbc.queryForList(
            "SELECT id, iso_code, french_label FROM [dbo].[pays] " +
            "WHERE (deleted=0 OR deleted IS NULL) ORDER BY iso_code");
    }

    private String fetchPaysIso(Long paysId) {
        try {
            return jdbc.queryForObject("SELECT iso_code FROM [dbo].[pays] WHERE id=?", String.class, paysId);
        } catch (Exception e) { return "?"; }
    }

    private void validateTypeCalcul(String type) {
        if (!List.of("WEEKEND_ONLY", "AFTER_WORK_HOURS", "MIXTE").contains(type)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "typeCalculHs invalide. Valeurs acceptées : WEEKEND_ONLY, AFTER_WORK_HOURS, MIXTE");
        }
    }

    private ParametrageHSDto toDto(ParametrageHSPays p) {
        String iso = fetchPaysIso(p.getPaysId());
        return ParametrageHSDto.builder()
                .idParametrage(p.getIdParametrage())
                .paysId(p.getPaysId())
                .paysIsoCode(iso)
                .typeCalculHs(p.getTypeCalculHs())
                .heureDebutTravail(p.getHeureDebutTravail())
                .heureFinTravail(p.getHeureFinTravail())
                .jourDebutSemaine(p.getJourDebutSemaine())
                .jourFinSemaine(p.getJourFinSemaine())
                .actif(p.getActif())
                .dateCreation(p.getDateCreation())
                .dateModification(p.getDateModification())
                .build();
    }
}
