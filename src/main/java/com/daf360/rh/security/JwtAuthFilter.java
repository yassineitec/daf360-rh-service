package com.daf360.rh.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Collect all candidate tokens — Bearer header, then HMAC cookie, then RSA cookie.
        // We try EACH in order and use the FIRST that validates successfully.
        // This handles: HMAC secret mismatch (Bearer fails → fall through to RSA cookie),
        // or missing daf360_rh (Bearer fails → RSA cookie validates), etc.
        String bearerToken    = null;
        String hmacCookieToken = null;
        String rsaCookieToken  = null;

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            bearerToken = authHeader.substring(7);
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("daf360_rh".equals(cookie.getName()))     hmacCookieToken = cookie.getValue();
                if ("daf360_access".equals(cookie.getName())) rsaCookieToken  = cookie.getValue();
            }
        }

        // Try each candidate until one validates
        String token  = null;
        String source = null;
        String[][] candidates = {
            {bearerToken,     "Bearer header"},
            {hmacCookieToken, "daf360_rh (HMAC cookie)"},
            {rsaCookieToken,  "daf360_access (RSA cookie)"},
        };
        for (String[] pair : candidates) {
            if (pair[0] != null) {
                if (jwtService.isTokenValid(pair[0])) {
                    token  = pair[0];
                    source = pair[1];
                    break;
                } else {
                    log.debug("JWT: {} invalid for {}", pair[1], request.getRequestURI());
                }
            }
        }

        if (token == null) {
            log.debug("JWT: no valid token for {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("JWT authenticated via {} for {}", source, request.getRequestURI());

        try {
            if (jwtService.isTokenValid(token)) {
                Claims claims = jwtService.parseToken(token);

                // Extract fine-grained permission codes from the `permissions` claim.
                // These are plain authority strings (e.g. "HR_CREATE_PROFILE") — no ROLE_ prefix.
                @SuppressWarnings("unchecked")
                List<String> permissions = claims.get("permissions", List.class);
                List<SimpleGrantedAuthority> authorities = permissions == null
                        ? new ArrayList<>()
                        : permissions.stream()
                                     .map(SimpleGrantedAuthority::new)
                                     .collect(Collectors.toList());

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                claims.getSubject(), null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Store paysId in ThreadLocal for multi-tenant filtering downstream.
                // The portal puts this claim in every JWT via AzureOAuth2SuccessHandler.
                Number paysIdClaim = claims.get("paysId", Number.class);
                if (paysIdClaim != null) {
                    TenantContext.set(paysIdClaim.longValue());
                }

                /*
                 * La PORTÉE PAYS du rôle (V74), au-delà du seul `paysId`.
                 *
                 * Le portail résout le mode du rôle (OWN / LIST / ALL) en un ensemble concret
                 * au moment de signer, et l'émet dans `paysScopeAll` + `paysIds`. Ce filtre ne
                 * lisait que `paysId`, donc un rôle en mode LIST couvrant TN + EG + AE était
                 * écrasé sur un seul pays alors que le jeton portait les trois.
                 *
                 * Repli sur `paysId` seul quand les revendications sont absentes (jeton
                 * antérieur à V74) : c'est le comportement d'avant, et non « tous les pays ».
                 */
                Boolean scopeAll = claims.get("paysScopeAll", Boolean.class);
                @SuppressWarnings("unchecked")
                List<Number> scopeIds = claims.get("paysIds", List.class);

                Set<Long> allowed = new LinkedHashSet<>();
                if (scopeIds != null) {
                    scopeIds.stream().filter(java.util.Objects::nonNull)
                            .forEach(n -> allowed.add(n.longValue()));
                }
                if (allowed.isEmpty() && paysIdClaim != null) {
                    allowed.add(paysIdClaim.longValue());
                }
                PaysScopeContext.set(new PaysScopeContext.Scope(
                        Boolean.TRUE.equals(scopeAll), allowed));

                log.debug("JWT authenticated: sub={}, paysId={}, permissions={}",
                        claims.getSubject(), paysIdClaim, permissions);
            }
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.debug("JWT filter error: {}", e.getMessage());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            // Même raison que TenantContext : le thread sert la requête suivante, et une
            // portée oubliée serait celle de l'utilisateur précédent.
            PaysScopeContext.clear();
        }
    }
}
