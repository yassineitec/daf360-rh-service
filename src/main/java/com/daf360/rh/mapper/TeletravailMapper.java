package com.daf360.rh.mapper;

import com.daf360.rh.domain.Teletravail;
import com.daf360.rh.dto.teletravail.TeletravailDto;
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
 * Entity to DTO, with the people's names filled in — one batched lookup per page, for the
 * same reason as {@link AutorisationMapper}: a 20-row queue resolving names per row would
 * issue 81 queries to render one page.
 */
@Component
@RequiredArgsConstructor
public class TeletravailMapper {

    private final JdbcTemplate jdbcTemplate;

    public TeletravailDto toDto(Teletravail t, String lang) {
        return toDtos(List.of(t), lang).get(0);
    }

    public List<TeletravailDto> toDtos(Collection<Teletravail> rows, String lang) {
        Set<Long> ids = new HashSet<>();
        for (Teletravail t : rows) {
            if (t.getCollaborateurId() != null) ids.add(t.getCollaborateurId());
            if (t.getResponsableId() != null)   ids.add(t.getResponsableId());
            if (t.getDecidedBy() != null)       ids.add(t.getDecidedBy());
            if (t.getCreatedBy() != null)       ids.add(t.getCreatedBy());
        }
        Map<Long, String> names = namesFor(ids);
        Map<Long, String> photos = photosFor(ids);

        return rows.stream().map(t -> TeletravailDto.builder()
                .id(t.getId())
                .collaborateurId(t.getCollaborateurId())
                .collaborateurNom(names.get(t.getCollaborateurId()))
                .collaborateurAvatarUrl(photos.get(t.getCollaborateurId()))
                .responsableId(t.getResponsableId())
                .responsableNom(names.get(t.getResponsableId()))
                .paysId(t.getPaysId())
                .dateTeletravail(t.getDateTeletravail())
                .category(t.getCategory() == null ? null : t.getCategory().name())
                .categoryLabel(t.getCategory() == null ? null : t.getCategory().getLabel(lang))
                .jours(t.getJours())
                .etatDemande(t.getEtatDemande() == null ? null : t.getEtatDemande().name())
                .etatLabel(etatLabel(t, lang))
                .reason(t.getReason())
                .motifRefus(t.getMotifRefus())
                .motifAnnulation(t.getMotifAnnulation())
                .dateValidation(t.getDateValidation())
                .decidedBy(t.getDecidedBy())
                .decidedByNom(names.get(t.getDecidedBy()))
                .createdBy(t.getCreatedBy())
                .createdByNom(names.get(t.getCreatedBy()))
                .settled(t.getCreatedBy() != null && !t.getCreatedBy().equals(t.getCollaborateurId()))
                .balanceDebited(t.isBalanceDebited())
                .createdAt(t.getCreatedAt())
                .build()).toList();
    }

    private String etatLabel(Teletravail t, String lang) {
        if (t.getEtatDemande() == null) return null;
        boolean fr = !"en".equalsIgnoreCase(lang);
        return switch (t.getEtatDemande()) {
            case EN_ATTENTE -> fr ? "En attente" : "Pending";
            case VALIDE     -> fr ? "Validée"    : "Approved";
            case REFUSE     -> fr ? "Refusée"    : "Rejected";
            case ARCHIVE    -> fr ? "Archivée"   : "Archived";
        };
    }

    private Map<Long, String> namesFor(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>(ids.size());
        // Ids come from the database, never from request input, so the IN list is safe.
        jdbcTemplate.query("SELECT id, fullName FROM Users WHERE id IN (" + inList(ids) + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("fullName")); });
        return out;
    }

    private Map<Long, String> photosFor(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>(ids.size());
        jdbcTemplate.query("SELECT user_id, photo_url FROM employee_profiles WHERE user_id IN (" + inList(ids) + ")",
                rs -> { out.put(rs.getLong("user_id"), rs.getString("photo_url")); });
        return out;
    }

    private static String inList(Set<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
    }
}
