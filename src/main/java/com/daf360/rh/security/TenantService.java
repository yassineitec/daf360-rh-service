package com.daf360.rh.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class TenantService {

    private static final String ADMIN_PERMISSION = "ADMIN_ROLES";

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_PERMISSION.equals(a.getAuthority()));
    }

    /**
     * Returns null for global admins (bypass all tenant filters),
     * or the current request's paysId for regular users.
     */
    public Long getEffectivePaysId() {
        return isAdmin() ? null : TenantContext.get();
    }
}
