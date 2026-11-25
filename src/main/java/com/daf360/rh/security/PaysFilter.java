package com.daf360.rh.security;

import java.util.Collections;
import java.util.List;

/**
 * Les pays retenus pour une requête JdbcTemplate, résolus par
 * {@link TenantService#resolvePaysList} — les drapeaux cochés dans la navbar, croisés avec
 * le périmètre du rôle.
 *
 * <p>{@code ids == null} = aucun filtre (rôle « Tous les pays ») ; sinon une liste jamais
 * vide. Seuls des {@code ?} entrent dans le SQL : les valeurs passent en paramètres.
 *
 * <pre>
 * PaysFilter pays = PaysFilter.of(tenantService.resolvePaysList(requested));
 * sql  += pays.and("c.pays_id");
 * args.addAll(pays.params());
 * </pre>
 */
public record PaysFilter(List<Long> ids) {

    public static PaysFilter of(List<Long> ids) {
        return new PaysFilter(ids == null || ids.isEmpty() ? null : List.copyOf(ids));
    }

    /** Vrai quand aucune clause de pays ne doit être ajoutée. */
    public boolean unfiltered() {
        return ids == null;
    }

    /** {@code " AND <column> IN (?, …) "}, ou une chaîne vide sans filtre. */
    public String and(String column) {
        if (ids == null) return " ";
        return " AND " + column + " IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ") ";
    }

    /** Les paramètres de {@link #and}, dans l'ordre ; vide sans filtre. */
    public List<Long> params() {
        return ids == null ? List.of() : ids;
    }

    /** Pour un {@code IN :paysIds} JPQL : jamais vide (SQL Server refuse {@code IN ()}). */
    public List<Long> idsOrPlaceholder() {
        return ids == null ? List.of(-1L) : ids;
    }
}
