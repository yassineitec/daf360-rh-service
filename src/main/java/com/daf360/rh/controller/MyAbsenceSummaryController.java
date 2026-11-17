package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.service.WorkingDayCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The caller's own absence recap for one year — the self-service « Mes absences » tab: one
 * KPI per desk (congés, télétravail, autorisations) and how the days spread over the months
 * and over the congé types. It brings back what the timesheet recap's absence donut used to
 * show, on the RH data the three desks now live in.
 *
 * Same counting rules as InternalAbsenceSummaryController: a congé counts the working days it
 * covers inside the year (the employee's entity weekend and holidays excluded, a half-day is
 * half a day), split per month the same way; a télétravail counts its stored `jours`; an
 * autorisation its minutes. VALIDE rows are the figures, EN_ATTENTE rows are reported apart
 * as « pending » — refused and archived ones count nowhere.
 *
 * Caller only: the id comes from the token, there is no user parameter. Gated like the three
 * `/mine` lists it summarises.
 */
@RestController
@RequiredArgsConstructor
public class MyAbsenceSummaryController {

    private static final Set<String> SICK_TYPES = Set.of("MALADIE", "MATERNITE", "PATERNITE");
    private static final String VALIDE = "VALIDE";
    private static final String EN_ATTENTE = "EN_ATTENTE";

    private final WorkingDayCalculator calendar;
    private final JdbcTemplate jdbcTemplate;

    public record TypeDays(String code, String label, BigDecimal days) {}

    public record CongeSummary(BigDecimal approvedDays, BigDecimal pendingDays, BigDecimal sickDays,
                               List<TypeDays> byType) {}

    public record TeletravailSummary(BigDecimal approvedDays, BigDecimal pendingDays) {}

    public record AutorisationSummary(long approvedMinutes, long pendingMinutes, long approvedCount) {}

    /** One calendar month, approved figures only. */
    public record MonthSummary(int month, BigDecimal congeDays, BigDecimal teletravailDays,
                               BigDecimal autorisationHours) {}

    public record AbsenceRecap(int year, CongeSummary conges, TeletravailSummary teletravail,
                               AutorisationSummary autorisations, List<MonthSummary> byMonth) {}

    /** GET /api/hr/absences/mine/summary?year=2026&lang=fr */
    @GetMapping("/api/hr/absences/mine/summary")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public AbsenceRecap mySummary(@RequestParam(required = false) Integer year,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        int y = year == null ? LocalDate.now().getYear() : year;
        if (y < 2000 || y > 2100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year out of range");
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to = LocalDate.of(y, 12, 31);
        boolean en = "en".equalsIgnoreCase(lang);

        BigDecimal[][] months = new BigDecimal[12][3];
        for (BigDecimal[] m : months) { m[0] = BigDecimal.ZERO; m[1] = BigDecimal.ZERO; m[2] = BigDecimal.ZERO; }

        // ── Congés ────────────────────────────────────────────────────────────
        BigDecimal[] conge = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO }; // approved, pending, sick
        Map<String, TypeDays> byType = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT a.pays_id, a.type, a.category, a.date_debut, a.date_fin, a.etat_demande,
                       t.label_fr, t.label_en
                FROM   [dbo].[absences] a
                LEFT JOIN [dbo].[AbsenceTypes] t ON t.code = a.type
                WHERE  a.collaborateur_id = ?
                  AND  a.etat_demande IN ('VALIDE', 'EN_ATTENTE')
                  AND  a.date_debut <= ? AND a.date_fin >= ?
                """, rs -> {
            Number pays = (Number) rs.getObject("pays_id");
            Long paysId = pays == null ? null : pays.longValue();
            LeaveCategory category = LeaveCategory.valueOf(rs.getString("category"));
            LocalDate start = max(rs.getDate("date_debut").toLocalDate(), from);
            LocalDate end = min(rs.getDate("date_fin").toLocalDate(), to);
            String type = rs.getString("type");

            if (EN_ATTENTE.equals(rs.getString("etat_demande"))) {
                conge[1] = conge[1].add(calendar.billableDays(start, end, category, paysId));
                return;
            }
            // Month by month, so a congé straddling two months lands in both.
            BigDecimal total = BigDecimal.ZERO;
            for (LocalDate s = start; !s.isAfter(end); s = s.withDayOfMonth(1).plusMonths(1)) {
                LocalDate e = min(s.withDayOfMonth(s.lengthOfMonth()), end);
                BigDecimal days = calendar.billableDays(s, e, category, paysId);
                months[s.getMonthValue() - 1][0] = months[s.getMonthValue() - 1][0].add(days);
                total = total.add(days);
            }
            conge[0] = conge[0].add(total);
            if (SICK_TYPES.contains(type)) conge[2] = conge[2].add(total);
            String label = en ? rs.getString("label_en") : rs.getString("label_fr");
            BigDecimal sum = total;
            byType.merge(type, new TypeDays(type, label == null ? type : label, sum),
                    (a, b) -> new TypeDays(a.code(), a.label(), a.days().add(b.days())));
        }, userId, to, from);

        // ── Télétravail ───────────────────────────────────────────────────────
        BigDecimal[] tt = { BigDecimal.ZERO, BigDecimal.ZERO };
        jdbcTemplate.query("""
                SELECT t.date_teletravail, t.jours, t.etat_demande
                FROM   [dbo].[teletravails] t
                WHERE  t.collaborateur_id = ?
                  AND  t.etat_demande IN ('VALIDE', 'EN_ATTENTE')
                  AND  t.date_teletravail BETWEEN ? AND ?
                """, rs -> {
            BigDecimal jours = rs.getBigDecimal("jours");
            if (jours == null) return;
            if (VALIDE.equals(rs.getString("etat_demande"))) {
                tt[0] = tt[0].add(jours);
                int m = rs.getDate("date_teletravail").toLocalDate().getMonthValue() - 1;
                months[m][1] = months[m][1].add(jours);
            } else {
                tt[1] = tt[1].add(jours);
            }
        }, userId, from, to);

        // ── Autorisations ─────────────────────────────────────────────────────
        long[] auto = { 0L, 0L, 0L }; // approved minutes, pending minutes, approved count
        long[] monthMinutes = new long[12];
        jdbcTemplate.query("""
                SELECT a.date_autorisation, a.duree_minutes, a.etat_demande
                FROM   [dbo].[autorisations] a
                WHERE  a.collaborateur_id = ?
                  AND  a.etat_demande IN ('VALIDE', 'EN_ATTENTE')
                  AND  a.date_autorisation BETWEEN ? AND ?
                """, rs -> {
            long minutes = rs.getLong("duree_minutes");
            if (VALIDE.equals(rs.getString("etat_demande"))) {
                auto[0] += minutes;
                auto[2]++;
                monthMinutes[rs.getDate("date_autorisation").toLocalDate().getMonthValue() - 1] += minutes;
            } else {
                auto[1] += minutes;
            }
        }, userId, from, to);

        List<MonthSummary> byMonth = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            byMonth.add(new MonthSummary(i + 1, months[i][0], months[i][1],
                    BigDecimal.valueOf(monthMinutes[i]).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP)));
        }
        List<TypeDays> types = byType.values().stream()
                .filter(t -> t.days().signum() > 0)
                .sorted((a, b) -> b.days().compareTo(a.days()))
                .toList();

        return new AbsenceRecap(y,
                new CongeSummary(conge[0], conge[1], conge[2], types),
                new TeletravailSummary(tt[0], tt[1]),
                new AutorisationSummary(auto[0], auto[1], auto[2]),
                byMonth);
    }

    private static LocalDate max(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }

    private static LocalDate min(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
