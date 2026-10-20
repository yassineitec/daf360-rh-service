package com.daf360.rh.controller;

import com.daf360.rh.domain.Teletravail;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.leave.LeaveWithdrawRequest;
import com.daf360.rh.export.XlsxExport;
import org.springframework.http.ResponseEntity;
import com.daf360.rh.dto.teletravail.TeletravailCreate;
import com.daf360.rh.dto.teletravail.TeletravailDecision;
import com.daf360.rh.dto.teletravail.TeletravailDto;
import com.daf360.rh.dto.teletravail.TeletravailQuotaDto;
import com.daf360.rh.dto.teletravail.TeletravailRulesDto;
import com.daf360.rh.mapper.TeletravailMapper;
import com.daf360.rh.service.TeletravailRulesAdminService;
import com.daf360.rh.service.TeletravailService;
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
 * Télétravail — remote work, one day or half a day per request.
 *
 * PERMISSIONS ARE THE CONGÉ CODES, UNCHANGED — the timesheet gated télétravail on exactly the
 * same set (ADD_LEAVE, GET_LEAVES, RESPONSE_LEAVE, DELETE_LEAVE, SETTLE_LEAVES,
 * GET_EMPLOYEES_LEAVES, GET_GLOBAL_LEAVES), so nobody needs a grant and nobody gains a right.
 * Same reasoning as {@link AutorisationController}.
 *
 * The timesheet's two `headers` endpoints had NO authority check at all; every endpoint here
 * has one.
 */
@RestController
@RequestMapping("/api/hr/teletravail")
@RequiredArgsConstructor
public class TeletravailController {

    private final TeletravailService service;
    private final TeletravailMapper mapper;
    private final TeletravailRulesAdminService rulesAdmin;

    /** Sortable columns, as an allow-list — the value reaches a JPA Sort. */
    private static final Set<String> SORTABLE = Set.of(
            "dateTeletravail", "category", "jours", "etatDemande", "createdAt", "dateValidation");

    // ═══ The form ════════════════════════════════════════════════════════════

    /** The rules in force for this employee, their balance, and the usage around a day. */
    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public TeletravailQuotaDto quota(@RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     Authentication auth) {
        return service.quotaOf(actorId(auth), date);
    }

    /** The caller's committed days from today — the form greys out the full ones. */
    @GetMapping("/committed")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public List<Map<String, Object>> committed(Authentication auth) {
        return service.committedFrom(actorId(auth), LocalDate.now());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADD_LEAVE')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeletravailDto create(@Valid @RequestBody TeletravailCreate dto,
                                 @RequestParam(defaultValue = "fr") String lang,
                                 Authentication auth) {
        return mapper.toDto(service.create(dto, actorId(auth)), lang);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('SETTLE_LEAVES')")
    public TeletravailDto update(@PathVariable Long id,
                                 @Valid @RequestBody TeletravailCreate dto,
                                 @RequestParam(defaultValue = "fr") String lang,
                                 Authentication auth) {
        return mapper.toDto(service.update(id, dto, actorId(auth), canSettle(auth)), lang);
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('ADD_LEAVE')")
    public TeletravailDto withdraw(@PathVariable Long id,
                                   @Valid @RequestBody LeaveWithdrawRequest body,
                                   @RequestParam(defaultValue = "fr") String lang,
                                   Authentication auth) {
        return mapper.toDto(service.withdraw(id, body.getMotif(), actorId(auth)), lang);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    @PutMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public TeletravailDto decide(@PathVariable Long id,
                                 @Valid @RequestBody TeletravailDecision body,
                                 @RequestParam(defaultValue = "fr") String lang,
                                 Authentication auth) {
        return mapper.toDto(service.decide(id, body, actorId(auth), canSettle(auth)), lang);
    }

    @PutMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('DELETE_LEAVE')")
    public TeletravailDto archive(@PathVariable Long id,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.archive(id, actorId(auth)), lang);
    }

    // ═══ Régularisation ══════════════════════════════════════════════════════

    @PostMapping("/settle/{collaborateurId}")
    @PreAuthorize("hasAuthority('SETTLE_LEAVES')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeletravailDto settle(@PathVariable Long collaborateurId,
                                 @Valid @RequestBody TeletravailCreate dto,
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
        return envelope(service.global(etat, from, to, PageRequest.of(page, size, sortOf(sort, dir))), lang);
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
        List<TeletravailDto> rows = mapper.toDtos(service.global(etat, from, to,
                PageRequest.of(0, XlsxExport.MAX_ROWS, sortOf(sort, dir))).getContent(), lang);
        boolean fr = !"en".equalsIgnoreCase(lang);
        return XlsxExport.response("teletravail.xlsx", fr ? "Télétravail" : "Remote work",
                fr ? List.of("Collaborateur", "Date", "Durée", "Jours", "État", "Responsable",
                             "Décidé le", "Décidé par", "Motif", "Motif de refus", "Motif d'annulation", "Saisi par")
                   : List.of("Employee", "Date", "Duration", "Days", "State", "Manager",
                             "Decided on", "Decided by", "Reason", "Rejection reason", "Cancellation reason", "Filed by"),
                rows.stream().map(d -> java.util.Arrays.<Object>asList(
                        d.getCollaborateurNom(), d.getDateTeletravail(), d.getCategoryLabel(), d.getJours(),
                        XlsxExport.stateLabel(d.getEtatDemande(), lang), d.getResponsableNom(),
                        d.getDateValidation(), d.getDecidedByNom(), d.getReason(), d.getMotifRefus(),
                        d.getMotifAnnulation(), d.getCreatedByNom())).toList());
    }

    @GetMapping("/counts")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> counts(@RequestParam(defaultValue = "mine") String scope, Authentication auth) {
        return service.counts(actorId(auth), scope);
    }

    // ═══ Administration — the per-entity rules ═══════════════════════════════

    @GetMapping("/admin/rules")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'ADMIN_LISTS', 'GET_PAYS')")
    public List<TeletravailRulesDto> rules() {
        return rulesAdmin.list();
    }

    @PutMapping("/admin/rules/{paysId}")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'ADMIN_LISTS')")
    public TeletravailRulesDto updateRules(@PathVariable Long paysId,
                                           @RequestBody TeletravailRulesDto body,
                                           Authentication auth) {
        return rulesAdmin.update(paysId, body, actorId(auth));
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    private static Sort sortOf(String key, String dir) {
        String field = (key != null && SORTABLE.contains(key)) ? key : "dateTeletravail";
        return Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }

    private Map<String, Object> envelope(Page<Teletravail> rows, String lang) {
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

    private boolean canSettle(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "SETTLE_LEAVES".equals(a.getAuthority()));
    }
}
