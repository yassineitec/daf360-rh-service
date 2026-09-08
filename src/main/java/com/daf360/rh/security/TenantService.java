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
        return isAdmin() ? null : TenantContext.get();
    }

    /**
     * Les pays que l'appelant peut voir, tels que le portail les a résolus dans le jeton.
     *
     * <p>C'est la seule forme correcte pour une LISTE : elle rend les trois modes de V74
     * (OWN / LIST / ALL) au lieu d'écraser sur un identifiant unique. Une permission
     * d'administration globale l'ouvre entièrement, comme {@link #getEffectivePaysId()}
     * renvoie null pour la même raison.
     */
    public PaysScopeContext.Scope getPaysScope() {
        if (isAdmin()) {
            return new PaysScopeContext.Scope(true, java.util.Set.of());
        }
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
