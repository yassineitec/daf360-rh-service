package com.daf360.rh.mapper;

import com.daf360.rh.domain.AbsenceType;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.repository.AbsenceTypeRepository;
import com.daf360.rh.dto.leave.LeaveRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity to DTO, with the people's names filled in.
 *
 * WHY THE NAMES ARE RESOLVED IN A BATCH
 * -----------------------------------------------------------------------------
 * Every list screen shows a person, never a user id, so each row needs up to three names —
 * the collaborateur, the responsable and whoever decided. Resolving them row by row would be
 * three queries per row: a 20-row manager queue would issue 61 queries to render one page.
 *
 * {@link #toDtos} collects the distinct ids across the whole page first and resolves them in
 * one statement. rh-service has no JPA entity for Users, so this goes through JdbcTemplate —
 * the same route MissionService takes for the same reason.
 */
@Component
@RequiredArgsConstructor
public class LeaveRequestMapper {

    private final JdbcTemplate jdbcTemplate;
    private final AbsenceTypeRepository absenceTypes;

    /** One row, resolving its names on their own. Use {@link #toDtos} for a page. */
    public LeaveRequestDto toDto(LeaveRequest e, String lang) {
        return toDto(e, lang, namesFor(idsIn(List.of(e))), typeLabels(lang));
    }

    /** A page, with every name resolved in a single extra query. */
    public List<LeaveRequestDto> toDtos(Collection<LeaveRequest> rows, String lang) {
        Map<Long, String> names = namesFor(idsIn(rows));
        Map<String, String> labels = typeLabels(lang);
        return rows.stream().map(e -> toDto(e, lang, names, labels)).toList();
    }

    private LeaveRequestDto toDto(LeaveRequest e, String lang, Map<Long, String> names,
                                 Map<String, String> typeLabels) {
        return new LeaveRequestDto(
                e.getId(),
                e.getCollaborateurId(),
                names.get(e.getCollaborateurId()),
                e.getResponsableId(),
                names.get(e.getResponsableId()),
                e.getPaysId(),
                e.getType(),
                e.getType() == null ? null : typeLabels.getOrDefault(e.getType(), e.getType()),
                e.getCategory() == null ? null : e.getCategory().name(),
                e.getCategory() == null ? null : e.getCategory().getLabel(lang),
                e.getDateDebut(),
                e.getDateFin(),
                e.getTotalJours(),
                e.getJustificatif(),
                e.getReason(),
                e.getEtatDemande() == null ? null : e.getEtatDemande().name(),
                e.getMotifRefus(),
                e.getDateValidation(),
                e.getDecidedBy(),
                names.get(e.getDecidedBy()),
                e.getCreatedBy(),
                names.get(e.getCreatedBy()),
                e.getCreatedAt());
    }

    /**
     * code -> label for the whole catalogue, read ONCE per page.
     *
     * Per row it would be one query each — the same N+1 the batched name lookup above
     * exists to avoid, and worse, because every row has a type while not every row has a
     * decider. The catalogue is a dozen rows.
     *
     * Deliberately not cached in a field: HR edits these labels in an admin screen, and a
     * cached map would print the old wording until the service restarted. A code with no
     * row falls back to the code itself rather than a blank cell — a retired type still has
     * to render in the history filed under it.
     */
    private Map<String, String> typeLabels(String lang) {
        Map<String, String> out = new HashMap<>();
        for (AbsenceType t : absenceTypes.findAllLive()) {
            out.put(t.getCode(), t.getLabel(lang));
        }
        return out;
    }

    private Set<Long> idsIn(Collection<LeaveRequest> rows) {
        Set<Long> ids = new HashSet<>();
        for (LeaveRequest e : rows) {
            if (e.getCollaborateurId() != null) ids.add(e.getCollaborateurId());
            if (e.getResponsableId() != null)   ids.add(e.getResponsableId());
            if (e.getDecidedBy() != null)       ids.add(e.getDecidedBy());
            if (e.getCreatedBy() != null)       ids.add(e.getCreatedBy());
        }
        return ids;
    }

    /**
     * id to fullName for a set of users.
     *
     * The IN list is built from Longs that came out of the database, never from request
     * input, so there is nothing to inject; binding them individually would mean a different
     * prepared statement for every distinct page size.
     */
    private Map<Long, String> namesFor(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, String> out = new HashMap<>(ids.size());
        jdbcTemplate.query(
                "SELECT id, fullName FROM Users WHERE id IN (" + inList + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("fullName")); });
        return out;
    }
}
