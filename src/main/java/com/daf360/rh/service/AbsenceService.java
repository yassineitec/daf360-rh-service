package com.daf360.rh.service;

import com.daf360.rh.dto.absence.AbsenceDto;
import com.daf360.rh.dto.absence.LeaveBalanceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AbsenceService {

    private static final Map<String, Double> DEFAULT_ALLOCATION = Map.of(
            "CONGE",        30.0,
            "MALADIE",       0.0,
            "MATERNITE",    98.0,
            "PATERNITE",    10.0,
            "EXCEPTIONNEL", 10.0,
            "DEUIL_AUTRE",   5.0
    );

    private final JdbcTemplate jdbcTemplate;

    // ── Leave balances ────────────────────────────────────────────────────────

    public List<LeaveBalanceDto> getLeaveBalances(Long profileId, Integer annee) {
        String sql = annee != null
                ? "SELECT type, COALESCE(SUM(total_jours), 0) AS jours_pris " +
                  "FROM [dbo].[absences] " +
                  "WHERE collaborateur_id = ? AND etat_demande = 'VALIDE' AND YEAR(date_debut) = ? " +
                  "GROUP BY type"
                : "SELECT type, COALESCE(SUM(total_jours), 0) AS jours_pris " +
                  "FROM [dbo].[absences] " +
                  "WHERE collaborateur_id = ? AND etat_demande = 'VALIDE' " +
                  "GROUP BY type";

        List<LeaveBalanceDto> rows = annee != null
                ? jdbcTemplate.query(sql, (rs, n) -> toBalance(rs.getString("type"), rs.getDouble("jours_pris")), profileId, annee)
                : jdbcTemplate.query(sql, (rs, n) -> toBalance(rs.getString("type"), rs.getDouble("jours_pris")), profileId);

        // Fill in leave types with zero absences so the UI always shows all types
        List<LeaveBalanceDto> result = new ArrayList<>(rows);
        for (String type : DEFAULT_ALLOCATION.keySet()) {
            boolean present = rows.stream().anyMatch(r -> type.equals(r.getLeaveType()));
            if (!present) {
                double allocation = DEFAULT_ALLOCATION.get(type);
                result.add(new LeaveBalanceDto(type, allocation, 0.0, allocation));
            }
        }
        return result;
    }

    private LeaveBalanceDto toBalance(String type, double joursPris) {
        double joursAcquis = DEFAULT_ALLOCATION.getOrDefault(type, 0.0);
        return new LeaveBalanceDto(type, joursAcquis, joursPris, Math.max(0, joursAcquis - joursPris));
    }

    // ── Absences (paginated) ──────────────────────────────────────────────────

    public Page<AbsenceDto> getAbsences(Long profileId, int page, int size) {
        int offset = page * size;

        List<AbsenceDto> content = jdbcTemplate.query(
                "SELECT id, type, date_debut, date_fin, etat_demande, total_jours, commentaire " +
                "FROM [dbo].[absences] " +
                "WHERE collaborateur_id = ? " +
                "ORDER BY date_debut DESC " +
                "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, n) -> {
                    Date debut = rs.getDate("date_debut");
                    Date fin   = rs.getDate("date_fin");
                    return new AbsenceDto(
                            rs.getLong("id"),
                            rs.getString("type"),
                            debut != null ? debut.toLocalDate() : null,
                            fin   != null ? fin.toLocalDate()   : null,
                            rs.getString("etat_demande"),
                            rs.getObject("total_jours") != null ? rs.getDouble("total_jours") : null,
                            rs.getString("commentaire")
                    );
                },
                profileId, offset, size);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[absences] WHERE collaborateur_id = ?",
                Integer.class, profileId);

        return new PageImpl<>(content, PageRequest.of(page, size), total != null ? total : 0);
    }
}
