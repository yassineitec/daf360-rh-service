package com.daf360.rh.mapper;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.dto.autorisation.AutorisationDto;
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
 * Entity to DTO, with the people's names filled in — one batched lookup per page.
 *
 * Same reasoning as {@link LeaveRequestMapper}: every list screen shows a person, never a
 * user id, and each row needs up to four names. Resolving them per row would be four queries
 * per row; a 20-row queue would issue 81 to render one page.
 *
 * Separate from LeaveRequestMapper rather than generalised over both: the two DTOs share no
 * fields beyond the names, and a shared mapper would need a type parameter for every
 * difference. The batching helper is what is worth repeating, and it is nine lines.
 */
@Component
@RequiredArgsConstructor
public class AutorisationMapper {

    private final JdbcTemplate jdbcTemplate;

    /** One row, resolving its own names. Use {@link #toDtos} for a page. */
    public AutorisationDto toDto(Autorisation a, String lang) {
        return toDtos(List.of(a), lang).get(0);
    }

    public List<AutorisationDto> toDtos(Collection<Autorisation> rows, String lang) {
        Set<Long> ids = new HashSet<>();
        for (Autorisation a : rows) {
            if (a.getCollaborateurId() != null) ids.add(a.getCollaborateurId());
            if (a.getResponsableId() != null)   ids.add(a.getResponsableId());
            if (a.getDecidedBy() != null)       ids.add(a.getDecidedBy());
            if (a.getCreatedBy() != null)       ids.add(a.getCreatedBy());
        }
        Map<Long, String> names = namesFor(ids);
        Map<Long, Face> faces = facesFor(ids);

        return rows.stream().map(a -> {
            Face face = faces.getOrDefault(a.getCollaborateurId(), new Face(null, null));
            return AutorisationDto.builder()
                    .id(a.getId())
                    .collaborateurId(a.getCollaborateurId())
                    .collaborateurNom(names.get(a.getCollaborateurId()))
                    .collaborateurAvatarUrl(face.photoUrl())
                    .responsableId(a.getResponsableId())
                    .responsableNom(names.get(a.getResponsableId()))
                    .paysId(a.getPaysId())
                    .dateAutorisation(a.getDateAutorisation())
                    .startTime(a.getStartTime())
                    .endTime(a.getEndTime())
                    .dureeMinutes(a.getDureeMinutes())
                    .etatDemande(a.getEtatDemande() == null ? null : a.getEtatDemande().name())
                    .etatLabel(etatLabel(a, lang))
                    .reason(a.getReason())
                    .motifRefus(a.getMotifRefus())
                    .motifAnnulation(a.getMotifAnnulation())
                    .dateValidation(a.getDateValidation())
                    .decidedBy(a.getDecidedBy())
                    .decidedByNom(names.get(a.getDecidedBy()))
                    .createdBy(a.getCreatedBy())
                    .createdByNom(names.get(a.getCreatedBy()))
                    // Filed by somebody other than the employee — a régularisation.
                    .settled(a.getCreatedBy() != null
                            && !a.getCreatedBy().equals(a.getCollaborateurId()))
                    .createdAt(a.getCreatedAt())
                    .build();
        }).toList();
    }

    /**
     * The state in the caller's language.
     *
     * Kept here rather than on DemandeEtat: that enum is shared with congés and carries no
     * labels, and adding them for one consumer would put presentation into a domain type.
     */
    private String etatLabel(Autorisation a, String lang) {
        if (a.getEtatDemande() == null) return null;
        boolean fr = !"en".equalsIgnoreCase(lang);
        return switch (a.getEtatDemande()) {
            case EN_ATTENTE -> fr ? "En attente" : "Pending";
            case VALIDE     -> fr ? "Validée"    : "Approved";
            case REFUSE     -> fr ? "Refusée"    : "Rejected";
            case ARCHIVE    -> fr ? "Archivée"   : "Archived";
        };
    }

    /** Photo per user, for the avatars. */
    private record Face(Long profileId, String photoUrl) {}

    private Map<Long, String> namesFor(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, String> out = new HashMap<>(ids.size());
        // Ids come from the database, never from request input, so the IN list is safe —
        // binding them individually would mean a new prepared statement per page size.
        jdbcTemplate.query("SELECT id, fullName FROM Users WHERE id IN (" + inList + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("fullName")); });
        return out;
    }

    private Map<Long, Face> facesFor(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        String inList = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, Face> out = new HashMap<>(ids.size());
        // LEFT-side lookup by user_id: not every user has an employee profile, and a request
        // filed for someone whose HR file was never created still has to render — as initials.
        jdbcTemplate.query(
                "SELECT user_id, id, photo_url FROM employee_profiles WHERE user_id IN (" + inList + ")",
                rs -> { out.put(rs.getLong("user_id"), new Face(rs.getLong("id"), rs.getString("photo_url"))); });
        return out;
    }
}
