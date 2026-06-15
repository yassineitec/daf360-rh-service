package com.daf360.rh.security;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Permission evaluator that backs //@PreAuthorize("hasPermission(null, 'PERMISSION_CODE')").
 *
 * Works with the rh-service auth model: JwtAuthFilter maps each permission string
 * from the JWT directly to a SimpleGrantedAuthority (no ROLE_ prefix), so we
 * check getAuthorities() for an exact string match.
 *
 * Usage: //@PreAuthorize("hasPermission(null, 'HR_CREATE_PROFILE')")
 */
@Component
public class HrPermissionEvaluator implements PermissionEvaluator {

    @Override
    public boolean hasPermission(Authentication auth, Object targetDomainObject, Object permission) {
        if (auth == null || !auth.isAuthenticated() || permission == null) return false;
        String code = permission.toString();
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(code));
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId,
                                  String targetType, Object permission) {
        return hasPermission(auth, null, permission);
    }
}
