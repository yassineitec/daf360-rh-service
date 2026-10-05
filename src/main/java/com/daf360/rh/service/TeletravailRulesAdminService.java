package com.daf360.rh.service;

import com.daf360.rh.dto.teletravail.TeletravailRulesDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Administering the per-entity télétravail rules (V116) — the sibling of
 * {@link AutorisationRulesAdminService}, scoped the same way: by the role's country perimeter,
 * read and write, so the screen can never offer an entity the save would refuse.
 *
 * The update takes the whole row and writes nulls through, because clearing a cap ("no
 * limit") has to be expressible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeletravailRulesAdminService {

    private static final Set<String> DURATIONS = Set.of("BOTH", "FULL_DAY", "HALF_DAY");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;

    @Transactional(readOnly = true)
    public List<TeletravailRulesDto> list() {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        boolean all = scope.all();
        List<Long> ids = all || scope.paysIds().isEmpty() ? List.of(-1L) : new ArrayList<>(scope.paysIds());
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1");

        // Only entities that matter — those with people, or already configured.
        String sql = """
                SELECT p.id, p.french_label, p.english_label, p.iso_code,
                       (SELECT COUNT(*) FROM [dbo].[Users] u
                         WHERE u.pays_id = p.id AND u.isActive = 1) AS employees,
                       p.tt_allowed_durations, p.tt_max_days_per_week, p.tt_max_days_per_month,
                       p.tt_notice_days, p.tt_uses_balance
                FROM   [dbo].[pays] p
                WHERE  ISNULL(p.deleted, 0) = 0
                  AND  (%s OR p.id IN (%s))
                  AND  (EXISTS (SELECT 1 FROM [dbo].[Users] u WHERE u.pays_id = p.id)
                        OR p.tt_max_days_per_week IS NOT NULL
                        OR p.tt_max_days_per_month IS NOT NULL)
                ORDER BY p.french_label
                """.formatted(all ? "1=1" : "1=0", inList);

        return jdbc.query(sql, (rs, n) -> TeletravailRulesDto.builder()
                .paysId(rs.getLong("id"))
                .frenchLabel(rs.getString("french_label"))
                .englishLabel(rs.getString("english_label"))
                .isoCode(rs.getString("iso_code"))
                .employees(rs.getLong("employees"))
                .allowedDurations(rs.getString("tt_allowed_durations") == null ? "BOTH" : rs.getString("tt_allowed_durations"))
                .maxDaysPerWeek(rs.getBigDecimal("tt_max_days_per_week"))
                .maxDaysPerMonth(rs.getBigDecimal("tt_max_days_per_month"))
                .noticeDays((Integer) rs.getObject("tt_notice_days"))
                .usesBalance(rs.getObject("tt_uses_balance") == null || rs.getBoolean("tt_uses_balance"))
                .build());
    }

    @Transactional
    public TeletravailRulesDto update(Long paysId, TeletravailRulesDto req, Long actorId) {
        assertInScope(paysId);
        String durations = validate(req);

        int rows = jdbc.update("""
                UPDATE [dbo].[pays]
                SET    tt_allowed_durations  = ?,
                       tt_max_days_per_week  = ?,
                       tt_max_days_per_month = ?,
                       tt_notice_days        = ?,
                       tt_uses_balance       = ?
                WHERE  id = ? AND ISNULL(deleted, 0) = 0
                """,
                durations, req.getMaxDaysPerWeek(), req.getMaxDaysPerMonth(), req.getNoticeDays(),
                !Boolean.FALSE.equals(req.getUsesBalance()), paysId);

        if (rows == 0) {
            throw new AppException(ErrorCode.NOT_FOUND, "Entité introuvable : " + paysId);
        }
        log.info("Télétravail rules updated for pays {} by user {}", paysId, actorId);

        return list().stream()
                .filter(d -> d.getPaysId().equals(paysId))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
    }

    /** Before the write: a configuration that would refuse every request is refused itself. */
    private String validate(TeletravailRulesDto r) {
        String d = r.getAllowedDurations() == null ? "BOTH" : r.getAllowedDurations().trim().toUpperCase();
        if (!DURATIONS.contains(d)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, "Durée autorisée inconnue : " + r.getAllowedDurations());
        }
        for (BigDecimal cap : new BigDecimal[] { r.getMaxDaysPerWeek(), r.getMaxDaysPerMonth() }) {
            if (cap == null) continue;
            if (cap.signum() <= 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Une limite doit être supérieure à zéro. Laissez le champ vide pour « aucune limite ».");
            }
            // Steps of half a day — the smallest thing anybody can ask for.
            if (cap.remainder(HALF).signum() != 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Une limite s'exprime en jours ou en demi-journées (ex. 2 ou 2,5).");
            }
            // A full-day-only entity capped at half a day would refuse every request.
            if ("FULL_DAY".equals(d) && cap.compareTo(BigDecimal.ONE) < 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Avec des journées entières uniquement, une limite inférieure à 1 jour refuserait toute demande.");
            }
        }
        if (r.getNoticeDays() != null && r.getNoticeDays() < 0) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, "Le délai de prévenance ne peut pas être négatif.");
        }
        if (r.getMaxDaysPerWeek() != null && r.getMaxDaysPerMonth() != null
                && r.getMaxDaysPerMonth().compareTo(r.getMaxDaysPerWeek()) < 0) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Le maximum mensuel ne peut pas être inférieur au maximum hebdomadaire.");
        }
        return d;
    }

    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.all()) return;
        if (paysId == null || !scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette entité est hors de votre périmètre.");
        }
    }
}
