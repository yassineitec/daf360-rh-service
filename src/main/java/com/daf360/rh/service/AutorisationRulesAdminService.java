package com.daf360.rh.service;

import com.daf360.rh.dto.autorisation.AutorisationRulesDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Administering the per-entity autorisation rules (V114).
 *
 * SCOPED BY THE ROLE'S COUNTRY PERIMETER, read and write.
 * -----------------------------------------------------------------------------
 * The screen is built from {@link #list()} and nothing else, so it can never offer an entity
 * the save would refuse — the trap the holidays screen fell into, where the list endpoint
 * accepted any pays the query string named while the screen was pinned to the caller's own.
 *
 * NULL IS "NO LIMIT", AND IT IS WRITABLE
 * -----------------------------------------------------------------------------
 * Clearing a cap has to be possible, so the update writes nulls through rather than treating
 * them as "leave unchanged". That is why it takes the whole row: a PATCH-style merge could
 * not express "remove this limit" at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutorisationRulesAdminService {

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;

    /** The entities this caller may administer, with their current rules. */
    @Transactional(readOnly = true)
    public List<AutorisationRulesDto> list() {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        boolean all = scope.all();
        List<Long> ids = all || scope.paysIds().isEmpty() ? List.of(-1L) : new ArrayList<>(scope.paysIds());
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1");

        // Only entities that matter — those with people in them, or already configured. The
        // reference table holds every ISO country; a list of 194 to reach the two you operate
        // in is a worse screen than one showing those two.
        String sql = """
                SELECT p.id, p.french_label, p.english_label, p.iso_code,
                       (SELECT COUNT(*) FROM [dbo].[Users] u
                         WHERE u.pays_id = p.id AND u.isActive = 1) AS employees,
                       p.autorisation_daily_minutes, p.autorisation_monthly_minutes,
                       p.autorisation_max_per_day, p.autorisation_notice_days,
                       p.autorisation_requires_justif
                FROM   [dbo].[pays] p
                WHERE  ISNULL(p.deleted, 0) = 0
                  AND  (%s OR p.id IN (%s))
                  AND  (EXISTS (SELECT 1 FROM [dbo].[Users] u WHERE u.pays_id = p.id)
                        OR p.autorisation_daily_minutes IS NOT NULL
                        OR p.autorisation_monthly_minutes IS NOT NULL)
                ORDER BY p.french_label
                """.formatted(all ? "1=1" : "1=0", inList);

        return jdbc.query(sql, (rs, n) -> AutorisationRulesDto.builder()
                .paysId(rs.getLong("id"))
                .frenchLabel(rs.getString("french_label"))
                .englishLabel(rs.getString("english_label"))
                .isoCode(rs.getString("iso_code"))
                .employees(rs.getLong("employees"))
                .dailyCapMinutes((Integer) rs.getObject("autorisation_daily_minutes"))
                .monthlyCapMinutes((Integer) rs.getObject("autorisation_monthly_minutes"))
                .maxPerDay((Integer) rs.getObject("autorisation_max_per_day"))
                .noticeDays((Integer) rs.getObject("autorisation_notice_days"))
                .requiresJustification(rs.getObject("autorisation_requires_justif") != null
                        && rs.getBoolean("autorisation_requires_justif"))
                .build());
    }

    /**
     * Writes one entity's rules.
     *
     * Validated before the write, not after: a negative cap or a monthly limit below the
     * daily one is a configuration that would refuse every request with a message naming
     * numbers nobody chose.
     */
    @Transactional
    public AutorisationRulesDto update(Long paysId, AutorisationRulesDto req, Long actorId) {
        assertInScope(paysId);
        validate(req);

        int rows = jdbc.update("""
                UPDATE [dbo].[pays]
                SET    autorisation_daily_minutes   = ?,
                       autorisation_monthly_minutes = ?,
                       autorisation_max_per_day     = ?,
                       autorisation_notice_days     = ?,
                       autorisation_requires_justif = ?
                WHERE  id = ? AND ISNULL(deleted, 0) = 0
                """,
                req.getDailyCapMinutes(), req.getMonthlyCapMinutes(), req.getMaxPerDay(),
                req.getNoticeDays(), Boolean.TRUE.equals(req.getRequiresJustification()),
                paysId);

        if (rows == 0) {
            throw new AppException(ErrorCode.NOT_FOUND, "Entité introuvable : " + paysId);
        }
        log.info("Autorisation rules updated for pays {} by user {}", paysId, actorId);

        return list().stream()
                .filter(d -> d.getPaysId().equals(paysId))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
    }

    private void validate(AutorisationRulesDto r) {
        if (nonPositive(r.getDailyCapMinutes()) || nonPositive(r.getMonthlyCapMinutes())
                || nonPositive(r.getMaxPerDay())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une limite doit être supérieure à zéro. Laissez le champ vide pour « aucune limite ».");
        }
        if (r.getNoticeDays() != null && r.getNoticeDays() < 0) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Le délai de prévenance ne peut pas être négatif.");
        }
        // A monthly cap below the daily one makes the daily one unreachable, so every request
        // using it would be refused by the monthly rule instead — correct, but baffling.
        if (r.getDailyCapMinutes() != null && r.getMonthlyCapMinutes() != null
                && r.getMonthlyCapMinutes() < r.getDailyCapMinutes()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Le plafond mensuel ne peut pas être inférieur au plafond journalier.");
        }
    }

    /** Zero is not a limit, it is a mistake — null is how "no limit" is expressed. */
    private boolean nonPositive(Integer v) {
        return v != null && v <= 0;
    }

    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.all()) return;
        if (paysId == null || !scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette entité est hors de votre périmètre.");
        }
    }
}
