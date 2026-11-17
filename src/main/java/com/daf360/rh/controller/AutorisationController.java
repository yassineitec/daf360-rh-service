package com.daf360.rh.controller;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.autorisation.AutorisationCreate;
import com.daf360.rh.dto.autorisation.AutorisationDecision;
import com.daf360.rh.dto.autorisation.AutorisationDto;
import com.daf360.rh.dto.autorisation.AutorisationQuotaDto;
import com.daf360.rh.dto.autorisation.AutorisationRulesDto;
import com.daf360.rh.dto.leave.LeaveWithdrawRequest;
import com.daf360.rh.export.XlsxExport;
import org.springframework.http.ResponseEntity;
import com.daf360.rh.mapper.AutorisationMapper;
import com.daf360.rh.service.AutorisationRulesAdminService;
import com.daf360.rh.service.AutorisationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Autorisations — short paid absences, in minutes.
 *
 * PERMISSIONS ARE THE CONGÉ CODES, UNCHANGED. The timesheet gated this module on exactly the
 * same set, so nobody needs a grant and nobody gains a right: whoever could file an
 * autorisation there can file one here. Inventing AUTORISATION_* codes would have meant a
 * migration whose first effect was taking the feature away from everyone until an
 * administrator noticed.
 *
 * SETTLE_LEAVES is the override, as throughout this module: it lets HR file on somebody's
 * behalf, edit a decided request, and decide one they are not the responsable for. Read from
 * the token, never taken as a parameter.
 */
@RestController
@RequestMapping("/api/hr/autorisations")
@RequiredArgsConstructor
public class AutorisationController {

    private final AutorisationService service;
    private final AutorisationMapper mapper;
    private final AutorisationRulesAdminService rulesAdmin;

    /** Sortable columns, as an allow-list — the value reaches a JPA Sort. */
    private static final Set<String> SORTABLE = Set.of(
            "dateAutorisation", "startTime", "dureeMinutes", "etatDemande", "createdAt", "dateValidation");

    // ═══ The form ════════════════════════════════════════════════════════════

    /** The caps in force for this employee and what is left of the monthly one. */
    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public AutorisationQuotaDto quota(Authentication auth) {
        return service.quotaOf(actorId(auth));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADD_LEAVE')")
    @ResponseStatus(HttpStatus.CREATED)
    public AutorisationDto create(@Valid @RequestBody AutorisationCreate dto,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.create(dto, actorId(auth)), lang);
    }

    /** Edit — own pending request, or anything with SETTLE_LEAVES. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('SETTLE_LEAVES')")
    public AutorisationDto update(@PathVariable Long id,
                                  @Valid @RequestBody AutorisationCreate dto,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.update(id, dto, actorId(auth), canSettle(auth)), lang);
    }

    /** The employee takes back their own pending request. ADD_LEAVE: whoever files may unfile. */
    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('ADD_LEAVE')")
    public AutorisationDto withdraw(@PathVariable Long id,
                                    @Valid @RequestBody LeaveWithdrawRequest body,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        return mapper.toDto(service.withdraw(id, body.getMotif(), actorId(auth)), lang);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    @PutMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public AutorisationDto decide(@PathVariable Long id,
                                  @Valid @RequestBody AutorisationDecision body,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.decide(id, body, actorId(auth), canSettle(auth)), lang);
    }

    @PutMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('DELETE_LEAVE')")
    public AutorisationDto archive(@PathVariable Long id,
                                   @RequestParam(defaultValue = "fr") String lang,
                                   Authentication auth) {
        return mapper.toDto(service.archive(id, actorId(auth)), lang);
    }

    // ═══ Régularisation ══════════════════════════════════════════════════════

    /** HR records an absence that already happened — filed already approved. */
    @PostMapping("/settle/{collaborateurId}")
    @PreAuthorize("hasAuthority('SETTLE_LEAVES')")
    @ResponseStatus(HttpStatus.CREATED)
    public AutorisationDto settle(@PathVariable Long collaborateurId,
                                  @Valid @RequestBody AutorisationCreate dto,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.settle(dto, collaborateurId, actorId(auth)), lang);
    }

    // ═══ The four queues ═════════════════════════════════════════════════════

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public Map<String, Object> mine(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String dir,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        return envelope(service.mine(actorId(auth), PageRequest.of(page, size, sortOf(sort, dir))), lang);
    }

    /** The approver's queue. */
    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('GET_LEAVES') or hasAuthority('RESPONSE_LEAVE')")
    public Map<String, Object> queue(@RequestParam(required = false) DemandeEtat etat,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     @RequestParam(required = false) String sort,
                                     @RequestParam(required = false) String dir,
                                     @RequestParam(defaultValue = "fr") String lang,
                                     Authentication auth) {
        return envelope(service.managerQueue(actorId(auth), etat,
                PageRequest.of(page, size, sortOf(sort, dir))), lang);
    }

    /** Everyone reporting into the caller, down the role hierarchy. */
    @GetMapping("/team")
    @PreAuthorize("hasAuthority('GET_EMPLOYEES_LEAVES')")
    public Map<String, Object> team(@RequestParam(required = false) DemandeEtat etat,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String dir,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        return envelope(service.team(actorId(auth), etat,
                PageRequest.of(page, size, sortOf(sort, dir))), lang);
    }

    /** Country-wide, bounded by the caller's role pays scope. */
    @GetMapping("/global")
    @PreAuthorize("hasAuthority('GET_GLOBAL_LEAVES')")
    public Map<String, Object> global(@RequestParam(required = false) DemandeEtat etat,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(required = false) String sort,
                                      @RequestParam(required = false) String dir,
                                      @RequestParam(defaultValue = "fr") String lang) {
        return envelope(service.global(etat, from, to,
                PageRequest.of(page, size, sortOf(sort, dir))), lang);
    }

    /** The global list as an Excel file — same filters and scope, unpaged up to MAX_ROWS. */
    @GetMapping("/global/export")
    @PreAuthorize("hasAuthority('GET_GLOBAL_LEAVES')")
    public ResponseEntity<byte[]> exportGlobal(@RequestParam(required = false) DemandeEtat etat,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam(required = false) String dir,
                                               @RequestParam(defaultValue = "fr") String lang) {
        List<AutorisationDto> rows = mapper.toDtos(service.global(etat, from, to,
                PageRequest.of(0, XlsxExport.MAX_ROWS, sortOf(sort, dir))).getContent(), lang);
        boolean fr = !"en".equalsIgnoreCase(lang);
        return XlsxExport.response("autorisations.xlsx", fr ? "Autorisations" : "Short absences",
                fr ? List.of("Collaborateur", "Date", "Début", "Fin", "Durée (min)", "État", "Responsable",
                             "Décidé le", "Décidé par", "Motif", "Motif de refus", "Motif d'annulation", "Saisi par")
                   : List.of("Employee", "Date", "Start", "End", "Duration (min)", "State", "Manager",
                             "Decided on", "Decided by", "Reason", "Rejection reason", "Cancellation reason", "Filed by"),
                rows.stream().map(d -> java.util.Arrays.<Object>asList(
                        d.getCollaborateurNom(), d.getDateAutorisation(), d.getStartTime(), d.getEndTime(),
                        d.getDureeMinutes(), XlsxExport.stateLabel(d.getEtatDemande(), lang), d.getResponsableNom(),
                        d.getDateValidation(), d.getDecidedByNom(), d.getReason(), d.getMotifRefus(),
                        d.getMotifAnnulation(), d.getCreatedByNom())).toList());
    }

    /** KPI counts for whichever queue is open. */
    @GetMapping("/counts")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> counts(@RequestParam(defaultValue = "mine") String scope,
                                    Authentication auth) {
        return service.counts(actorId(auth), scope);
    }

    // ═══ Administration — the per-entity rules ═══════════════════════════════

    /**
     * The entities this caller may administer, with their quotas.
     *
     * The admin screen is built from this and nothing else, so it can never offer an entity
     * the save would refuse — the trap the holidays screen fell into.
     */
    @GetMapping("/admin/rules")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'ADMIN_LISTS', 'GET_PAYS')")
    public List<AutorisationRulesDto> rules() {
        return rulesAdmin.list();
    }

    @PutMapping("/admin/rules/{paysId}")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'ADMIN_LISTS')")
    public AutorisationRulesDto updateRules(@PathVariable Long paysId,
                                            @RequestBody AutorisationRulesDto body,
                                            Authentication auth) {
        return rulesAdmin.update(paysId, body, actorId(auth));
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    private static Sort sortOf(String key, String dir) {
        String field = (key != null && SORTABLE.contains(key)) ? key : "dateAutorisation";
        return Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }

    private Map<String, Object> envelope(Page<Autorisation> rows, String lang) {
        return Map.of(
                "content", mapper.toDtos(rows.getContent(), lang),
                "page", rows.getNumber(),
                "size", rows.getSize(),
                "totalElements", rows.getTotalElements(),
                "totalPages", rows.getTotalPages());
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new AccessDeniedException("Utilisateur non authentifié");
        }
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Identité utilisateur illisible");
        }
    }

    /** Read from the token rather than trusted from a parameter. */
    private boolean canSettle(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "SETTLE_LEAVES".equals(a.getAuthority()));
    }
}
