package com.daf360.rh.security;

import java.util.List;
import java.util.Set;

/**
 * Les pays que l'appelant a le droit de voir, pour la durée d'une requête.
 *
 * POURQUOI PAS SEULEMENT `TenantContext`
 * -----------------------------------------------------------------------------
 * `TenantContext` ne porte qu'un identifiant : celui de l'utilisateur
 * ({@code Users.pays_id}). C'était suffisant avant V74, quand un rôle voyait son pays et
 * rien d'autre. V74 a introduit trois modes sur le rôle ({@link com.daf360.rh.domain.PaysScopeMode}) :
 *
 *   · OWN  — le pays de l'utilisateur, différent pour chaque porteur du rôle ;
 *   · LIST — exactement les pays inscrits sur le rôle, les mêmes pour tous ses porteurs ;
 *   · ALL  — aucun filtre.
 *
 * Le portail résout ce mode en un ENSEMBLE CONCRET au moment où il signe le jeton, et
 * l'émet dans les revendications `paysScopeAll` et `paysIds`. Le service de facturation les
 * lit déjà (`PaysIsolationInterceptor`) ; rh-service, lui, ne lisait que `paysId`. Un rôle
 * « RH » couvrant légitimement TN + EG + AE se retrouvait donc écrasé sur un seul pays,
 * alors que le jeton disait le contraire.
 *
 * CE QU'ON N'INVENTE PAS ICI
 * -----------------------------------------------------------------------------
 * Le mode n'est pas relu en base : le jeton porte déjà la réponse, et la relire ouvrirait
 * une divergence entre ce que le portail a signé et ce que ce service décide. Un jeton
 * antérieur à V74 n'a pas ces revendications — on retombe alors sur `paysId` seul, c'est-à-dire
 * le comportement d'avant, jamais sur « tous les pays ».
 */
public final class PaysScopeContext {

    /**
     * @param all      l'appelant voit tous les pays — mode ALL, ou une permission
     *                 d'administration globale (voir {@code TenantService})
     * @param paysIds  les pays autorisés quand {@code all} est faux. Vide ⇒ voir
     *                 {@link Scope#isUnresolved()}
     */
    public record Scope(boolean all, Set<Long> paysIds) {

        /**
         * Ni « tous », ni un seul pays connu : l'entité de l'appelant n'a pas pu être
         * résolue (jeton sans `paysId`, principal de développement).
         *
         * <p>Traité comme PERMISSIF, volontairement, et c'est le comportement d'avant :
         * `TenantService.getEffectivePaysId()` renvoyait déjà null dans ce cas, ce qui
         * n'appliquait aucun filtre. Le durcir ici viderait les écrans de tout utilisateur
         * dont le jeton est incomplet — une panne bien plus visible qu'une fuite, et sur
         * une donnée que tout le monde dans l'entreprise peut de toute façon consulter
         * dans l'annuaire.
         */
        public boolean isUnresolved() {
            return !all && (paysIds == null || paysIds.isEmpty());
        }

        /** Vrai quand aucune clause de pays ne doit être ajoutée à la requête. */
        public boolean unfiltered() {
            return all || isUnresolved();
        }

        /** Pour une requête paramétrée : jamais vide (SQL Server refuse un `IN ()`). */
        public List<Long> idsOrPlaceholder() {
            return unfiltered() ? List.of(-1L) : List.copyOf(paysIds);
        }
    }

    private PaysScopeContext() {}

    private static final ThreadLocal<Scope> HOLDER = new ThreadLocal<>();

    public static void set(Scope scope) { HOLDER.set(scope); }
    public static Scope get()           { return HOLDER.get(); }
    public static void clear()          { HOLDER.remove(); }
}
