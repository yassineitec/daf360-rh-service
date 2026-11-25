package com.daf360.rh.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class TenantService {

    private static final String ADMIN_PERMISSION = "ADMIN_ROLES";

    /**
     * Ajoutée par V84 « for cross-country admin access » — c'est la permission
     * explicitement créée pour voir au-delà de son pays, alors que {@code ADMIN_ROLES}
     * parle d'administration des rôles et ne s'était trouvée là que faute de mieux. Les
     * deux ouvrent la portée : retirer la seconde priverait d'un coup les administrateurs
     * en place, sans que personne n'ait demandé ce changement.
     */
    private static final String SUPER_ADMIN_PERMISSION = "RH_SUPER_ADMIN";

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_PERMISSION.equals(a.getAuthority())
                            || SUPER_ADMIN_PERMISSION.equals(a.getAuthority()));
    }

    /**
     * Returns null for global admins (bypass all tenant filters),
     * or the current request's paysId for regular users.
     *
     * <p>Ne voit QU'UN pays. Conservée telle quelle pour ses appelants existants
     * (candidats, entretiens), mais toute nouvelle liste doit passer par
     * {@link #getPaysScope()} : un rôle en mode LIST couvre plusieurs pays, et cette
     * méthode en perd tous sauf un.
     */
    public Long getEffectivePaysId() {
        // Le PÉRIMÈTRE DU RÔLE décide, plus la permission : un admin dont le rôle est en
        // « Pays de l'utilisateur » ou en liste reste sur son pays ici, comme tout le monde.
        // Seul un rôle en « Tous les pays » obtient null (= aucun filtre).
        return getPaysScope().all() ? null : TenantContext.get();
    }

    /**
     * Ce pays est-il dans le périmètre du rôle de l'appelant ?
     * {@code null} n'est pas un pays : réponse {@code false}.
     */
    public boolean allows(Long paysId) {
        if (paysId == null) return false;
        PaysScopeContext.Scope scope = getPaysScope();
        return scope.unfiltered() || scope.paysIds().contains(paysId);
    }

    /**
     * Le pays à utiliser pour une requête qui en demande un — ce que faisait
     * {@code isAdmin() ? demandé : propre pays}, mais selon le PÉRIMÈTRE DU RÔLE :
     * <ul>
     *   <li>un pays demandé et permis est honoré (y compris un pays de la liste d'un rôle LIST) ;</li>
     *   <li>un pays hors périmètre retombe sur le pays de l'appelant, jamais celui demandé ;</li>
     *   <li>rien de demandé : {@code null} (tous) pour un rôle « Tous les pays », sinon le
     *       pays de l'appelant.</li>
     * </ul>
     */
    public Long resolvePays(Long requestedPaysId) {
        if (requestedPaysId == null) return getEffectivePaysId();
        return allows(requestedPaysId) ? requestedPaysId : TenantContext.get();
    }

    /**
     * Version multi-pays de {@link #resolvePays(Long)} : les pays cochés dans la navbar.
     * Renvoie {@code null} pour « aucun filtre », sinon une liste jamais vide.
     * <ul>
     *   <li>rien de demandé (« Tous les pays ») : {@code null} pour un rôle ALL (ou une portée
     *       non résolue, permissive comme ailleurs), sinon TOUS les pays du périmètre — un
     *       rôle « Liste de pays » voit donc ses pays, pas seulement le sien ;</li>
     *   <li>des pays demandés : ceux qui sont dans le périmètre ; si aucun ne l'est, le pays
     *       de l'appelant (même repli que {@link #resolvePays(Long)}).</li>
     * </ul>
     */
    public java.util.List<Long> resolvePaysList(java.util.Collection<Long> requested) {
        PaysScopeContext.Scope scope = getPaysScope();
        java.util.List<Long> asked = requested == null ? java.util.List.of()
                : requested.stream().filter(java.util.Objects::nonNull).filter(id -> id > 0)
                           .distinct().toList();
        if (asked.isEmpty()) {
            return scope.unfiltered() ? null : java.util.List.copyOf(new java.util.TreeSet<>(scope.paysIds()));
        }
        java.util.List<Long> allowed = asked.stream().filter(this::allows).toList();
        if (!allowed.isEmpty()) return allowed;
        Long own = TenantContext.get();
        return own != null ? java.util.List.of(own) : java.util.List.copyOf(scope.paysIds());
    }

    /**
     * Les pays que l'appelant peut voir, tels que le portail les a résolus dans le jeton.
     *
     * <p>C'est la seule forme correcte pour une LISTE : elle rend les trois modes de V74
     * (OWN / LIST / ALL) au lieu d'écraser sur un identifiant unique. Elle s'applique aussi
     * aux administrateurs : seul un rôle en mode ALL voit tous les pays.
     */
    public PaysScopeContext.Scope getPaysScope() {
        // PLUS D'EXCEPTION ADMIN (2026-11-23). ADMIN_ROLES / RH_SUPER_ADMIN ouvraient tous les
        // pays quel que soit le « Périmètre pays » du rôle : un rôle Administrateur réglé sur
        // « Pays de l'utilisateur » voyait quand même tout. C'est désormais le périmètre que le
        // portail a mis dans le jeton (OWN / LIST / ALL) qui décide, pour tous les rôles — un
        // administrateur groupe doit donc avoir un rôle en « Tous les pays ».
        // `isAdmin()` reste pour ce qui n'est pas une question de pays (droit d'agir).
        PaysScopeContext.Scope scope = PaysScopeContext.get();
        if (scope != null) return scope;

        // Pas de portée posée par le filtre (jeton sans revendication, appel interne) :
        // on retombe sur le pays unique, donc sur le comportement d'avant V74.
        Long own = TenantContext.get();
        return own != null
                ? new PaysScopeContext.Scope(false, java.util.Set.of(own))
                : new PaysScopeContext.Scope(false, java.util.Set.of());
    }
}
