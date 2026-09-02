package com.daf360.rh.controller;

import com.daf360.rh.service.AdminUserService;
import com.daf360.rh.service.ModuleSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Administration → Utilisateurs: the account register.
 *
 * Gated on GET_USERS / CREATE_USER / UPDATE_USER, which already exist in PermissionCatalog and
 * in the RolePermissions check constraint — no new permission code, so nothing to seed or grant
 * beyond what an administrator already holds.
 */
@RestController
@RequestMapping("/api/hr/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService  service;
    private final ModuleSyncService moduleSync;

    /**
     * GET /api/hr/admin/users?search=&isEmployee=&paysId=&onlyMissingProfile=
     *
     * Unfiltered on purpose: this is the one screen where a non-employee account must be
     * visible, since it is where it gets flagged.
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'GET_USERS')")
    public ResponseEntity<List<AdminUserService.AdminUserRow>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isEmployee,
            @RequestParam(required = false) Long paysId,
            @RequestParam(required = false) Boolean onlyMissingProfile) {
        return ResponseEntity.ok(service.list(search, isEmployee, paysId, onlyMissingProfile));
    }

    /** GET /api/hr/admin/users/stats — header counters. */
    @GetMapping("/stats")
    @PreAuthorize("hasPermission(null, 'GET_USERS')")
    public ResponseEntity<AdminUserService.AdminUserStats> stats() {
        return ResponseEntity.ok(service.stats());
    }

    /**
     * POST /api/hr/admin/users — create an account without going through recruitment.
     *
     * Creates the DAF360 row only: sign-in is Azure AD and matches on the UPN, so the person
     * cannot log in until an Azure account with the same address exists. No HR profile is
     * created either — the account shows as « fiche RH manquante », which is the truth.
     */
    @PostMapping
    @PreAuthorize("hasPermission(null, 'CREATE_USER')")
    public ResponseEntity<AdminUserService.AdminUserRow> create(
            @RequestBody AdminUserService.CreateUserRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, actorId(auth)));
    }

    /**
     * PATCH /api/hr/admin/users/{id}/is-employee
     * Body: { "isEmployee": false }
     *
     * The write with the widest reach in the application: clearing the flag removes the
     * account from every picker and every notification recipient list at once. It does NOT
     * deactivate the account — a machine account keeps working, it just stops being offered
     * as a human.
     */
    @PatchMapping("/{id}/is-employee")
    @PreAuthorize("hasPermission(null, 'UPDATE_USER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setIsEmployee(@PathVariable Long id,
                              @RequestBody Map<String, Boolean> body,
                              Authentication auth) {
        service.setIsEmployee(id, body.get("isEmployee"), actorId(auth));
    }

    /**
     * POST /api/hr/admin/users/propagate
     *
     * Pousse l'etat courant des comptes vers finance et la paie sans attendre leur pull de
     * 15 minutes. C'est le geste d'urgence : couper un acces doit prendre effet tout de
     * suite, pas au prochain quart d'heure.
     *
     * Renvoie TOUJOURS 200, avec le detail par module. Un module injoignable n'est pas une
     * erreur de cette requete : le changement RH est deja enregistre, et le pull de chaque
     * consommateur le rattrapera. Renvoyer 500 laisserait croire que la modification a
     * echoue, ce qui serait faux et bien plus grave.
     *
     * Le jeton de l'appelant est transmis tel quel : chaque module applique ses propres
     * regles d'acces (celui de la paie exige SUPER_ADMIN, cf. ModuleSyncService).
     */
    @PostMapping("/propagate")
    @PreAuthorize("hasPermission(null, 'UPDATE_USER')")
    public ResponseEntity<List<ModuleSyncService.ModuleSyncResult>> propagate(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok(moduleSync.propagateUsers(authorization));
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
