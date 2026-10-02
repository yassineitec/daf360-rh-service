package com.daf360.rh.mapper;

import com.daf360.rh.domain.AbsenceType;
import com.daf360.rh.domain.LeaveModificationRequest;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.ModificationType;
import com.daf360.rh.repository.AbsenceTypeRepository;
import com.daf360.rh.dto.leave.LeaveModificationDto;
import com.daf360.rh.dto.leave.LeaveRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    /**
     * What it takes to draw one employee's avatar. Null fields are normal — a user with no
     * employee profile has no photo and no recorded gender, and the UI falls back to initials.
     */
    private record Face(Long profileId, String photoUrl, String gender) {}

    /** One row, resolving its names on their own. Use {@link #toDtos} for a page. */
    public LeaveRequestDto toDto(LeaveRequest e, String lang) {
        Set<Long> ids = idsIn(List.of(e));
        return toDto(e, lang, namesFor(ids), facesFor(ids), typeLabels(lang));
    }

    /** A page, with every name resolved in a single extra query. */
    public List<LeaveRequestDto> toDtos(Collection<LeaveRequest> rows, String lang) {
        Set<Long> ids = idsIn(rows);
        Map<Long, String> names = namesFor(ids);
        Map<Long, Face> faces = facesFor(ids);
        Map<String, String> labels = typeLabels(lang);
        return rows.stream().map(e -> toDto(e, lang, names, faces, labels)).toList();
    }

    private LeaveRequestDto toDto(LeaveRequest e, String lang, Map<Long, String> names,
                                 Map<Long, Face> faces, Map<String, String> typeLabels) {
        Face face = faces.getOrDefault(e.getCollaborateurId(), new Face(null, null, null));
        return new LeaveRequestDto(
                e.getId(),
                e.getCollaborateurId(),
                names.get(e.getCollaborateurId()),
                face.profileId(),
                face.photoUrl(),
                face.gender(),
                e.getResponsableId(),
                names.get(e.getResponsableId()),
                e.getResponsableAdjointId(),
                e.getPaysId(),
                e.getType(),
                e.getType() == null ? null : typeLabels.getOrDefault(e.getType(), e.getType()),
                e.getCategory() == null ? null : e.getCategory().name(),
                e.getCategory() == null ? null : e.getCategory().getLabel(lang),
                e.getDateDebut(),
                e.getDateFin(),
                e.getTotalJours(),
                e.getJustificatif(),
                e.getJustificatifDocumentId(),
                e.getReason(),
                e.getEtatDemande() == null ? null : e.getEtatDemande().name(),
                e.getMotifRefus(),
                e.getMotifAnnulation(),
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

    // ═══ Modification proposals ══════════════════════════════════════════════

    /**
     * A page of modification proposals, each paired with the congé it stands against.
     *
     * Takes the leaves as a map the caller has already loaded rather than loading them here:
     * the service needs them anyway to compute the delta, and fetching them twice would undo
     * the batching this class exists for. A proposal whose leave is missing from the map is
     * still rendered — the proposal is the row, and a blank "original" column is more useful
     * than a 500.
     */
    public List<LeaveModificationDto> toModificationDtos(Collection<LeaveModificationRequest> rows,
                                                         Map<Long, LeaveRequest> leaves,
                                                         String lang) {
        Set<Long> ids = new HashSet<>();
        for (LeaveModificationRequest r : rows) {
            if (r.getCollaborateurId() != null) ids.add(r.getCollaborateurId());
            if (r.getResponsableId() != null)   ids.add(r.getResponsableId());
        }
        Map<Long, String> names = namesFor(ids);
        Map<Long, Face> faces = facesFor(ids);
        Map<String, String> labels = typeLabels(lang);

        return rows.stream()
                .map(r -> toModificationDto(r, leaves.get(r.getAbsenceId()), lang, names, faces, labels))
                .toList();
    }

    /** One row, resolving its own names. Use {@link #toModificationDtos} for a page. */
    public LeaveModificationDto toModificationDto(LeaveModificationRequest r, LeaveRequest leave,
                                                  String lang) {
        return toModificationDtos(List.of(r),
                leave == null ? Map.of() : Map.of(leave.getId(), leave), lang).get(0);
    }

    private LeaveModificationDto toModificationDto(LeaveModificationRequest r, LeaveRequest leave,
                                                   String lang, Map<Long, String> names,
                                                   Map<Long, Face> faces,
                                                   Map<String, String> typeLabels) {
        Face face = faces.getOrDefault(r.getCollaborateurId(), new Face(null, null, null));

        // What the change costs or gives back. Null when either side is unknown rather than
        // zero: "no change" and "cannot say" are different answers, and an approver reading a
        // confident 0 for a leave that failed to load would be misinformed.
        BigDecimal delta = null;
        if (leave != null && leave.getTotalJours() != null) {
            BigDecimal proposed = r.getType() == ModificationType.ANNULATION
                    ? BigDecimal.ZERO                  // a cancellation returns the whole leave
                    : r.getNouveauTotal();
            if (proposed != null) {
                delta = proposed.subtract(leave.getTotalJours());
            }
        }

        return LeaveModificationDto.builder()
                .id(r.getId())
                .type(r.getType() == null ? null : r.getType().name())
                .typeLabel(r.getType() == null ? null : r.getType().getLabel(lang))
                .etat(r.getEtat() == null ? null : r.getEtat().name())
                .etatLabel(r.getEtat() == null ? null : r.getEtat().getLabel(lang))
                .nouvelleDateDebut(r.getNouvelleDateDebut())
                .nouvelleDateFin(r.getNouvelleDateFin())
                .nouveauTotal(r.getNouveauTotal())
                .deltaJours(delta)
                .motif(r.getMotif())
                .motifRefus(r.getMotifRefus())
                .absenceId(r.getAbsenceId())
                .absenceType(leave == null ? null : leave.getType())
                .absenceTypeLabel(leave == null || leave.getType() == null
                        ? null : typeLabels.getOrDefault(leave.getType(), leave.getType()))
                .absenceDateDebut(leave == null ? null : leave.getDateDebut())
                .absenceDateFin(leave == null ? null : leave.getDateFin())
                .absenceTotalJours(leave == null ? null : leave.getTotalJours())
                .collaborateurId(r.getCollaborateurId())
                .collaborateurNom(names.get(r.getCollaborateurId()))
                .collaborateurAvatarUrl(face.photoUrl())
                .responsableId(r.getResponsableId())
                .responsableNom(names.get(r.getResponsableId()))
                .createdAt(r.getCreatedAt())
                .dateValidation(r.getDateValidation())
                .build();
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

    /**
     * Photo + gender per user, for the avatars — batched exactly like {@link #namesFor}.
     *
     * A LEFT-side lookup by `user_id` rather than a join from Users, because not every user has
     * an employee profile: a congé filed for someone whose HR file was never created still has
     * to render, and it renders as initials. Ids come from the database, never from request
     * input, so the IN list is safe for the same reason namesFor's is.
     *
     * One query per page, not per row. Resolving this inside the row mapper would be the N+1
     * the whole class is shaped to avoid.
     */
    private Map<Long, Face> facesFor(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, Face> out = new HashMap<>(ids.size());
        jdbcTemplate.query(
                "SELECT user_id, id, photo_url, gender FROM employee_profiles WHERE user_id IN ("
                        + inList + ")",
                rs -> {
                    out.put(rs.getLong("user_id"),
                            new Face(rs.getLong("id"), rs.getString("photo_url"), rs.getString("gender")));
                });
        return out;
    }
}
