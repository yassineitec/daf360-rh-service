package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.dto.candidate.*;
import com.daf360.rh.dto.email.CandidateEmailDtos;
import com.daf360.rh.dto.offer.CreateOfferRequest;
import com.daf360.rh.dto.offer.OfferResponse;
import com.daf360.rh.dto.offer.RejectOfferRequest;
import com.daf360.rh.service.CandidateService;
import com.daf360.rh.service.OfferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;

@RestController
@RequestMapping("/api/hr/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateService candidateService;
    private final OfferService offerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse create(@Valid @RequestBody CreateCandidateRequest request,
                                    Authentication auth) {
        return candidateService.createCandidate(request, actorId(auth));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('VIEW_CANDIDATES','HR_ONBOARDING','IT_PROVISIONING','ADMIN_ROLES')")
    public Page<CandidateListItem> list(
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(required = false) String stage,
            // Un ou plusieurs pays (drapeaux de la navbar) : paysId=179,64.
            @RequestParam(required = false) java.util.List<Long> paysId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long demandId,
            @RequestParam(defaultValue = "false") boolean spontaneous,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @PageableDefault(size = 12) Pageable pageable) {
        return candidateService.listCandidates(status, stage, paysId, search,
                new CandidateListFilter(departmentId, demandId, spontaneous, createdFrom, createdTo),
                pageable);
    }

    /** KPI tiles for the /candidates dashboard (total + growth, avg delay, urgent positions). */
    @GetMapping("/stats/dashboard")
    public CandidateDashboardStats dashboardStats(
            @RequestParam(required = false) java.util.List<Long> paysId) {
        return candidateService.getDashboardStats(paysId);
    }

    /**
     * HR_ONBOARDING / IT_PROVISIONING as before, or — since V132 — a member of one of this
     * candidate's interview panels, who is served a reduced record (no salary, no identity
     * documents, no IT). Checked in the service: it depends on the row, not the caller alone.
     */
    @GetMapping("/{id}")
    public CandidateResponse get(@PathVariable Long id) {
        return candidateService.getCandidateForViewer(id);
    }

    /**
     * What the caller may do on this candidate — the frontend route guard asks before opening
     * the page, so an interviewer with no RH code is let in and anyone else is sent to
     * /forbidden instead of landing on a page of 403s.
     */
    @GetMapping("/{id}/access")
    public CandidateAccessDto access(@PathVariable Long id) {
        return candidateService.accessOf(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse update(@PathVariable Long id,
                                    @Valid @RequestBody UpdateCandidateRequest request,
                                    Authentication auth) {
        return candidateService.updateCandidate(id, request, actorId(auth));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse accept(@PathVariable Long id, Authentication auth) {
        return candidateService.acceptCandidate(id, actorId(auth));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse reject(@PathVariable Long id,
                                    @Valid @RequestBody RejectCandidateRequest request,
                                    Authentication auth) {
        return candidateService.rejectCandidate(id, request, actorId(auth));
    }

    /** Upload CV (PDF / DOC / DOCX, max 10 MB). Replaces any previous file. */
    @PostMapping(value = "/{id}/cv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'EDIT_CANDIDATE') or hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse uploadCv(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      Authentication auth) {
        return candidateService.uploadCv(id, file, actorId(auth));
    }

    /** Download the candidate's CV file. */
    @GetMapping("/{id}/cv")
    public ResponseEntity<Resource> downloadCv(@PathVariable Long id) {
        // VIEW_CANDIDATES / HR_ONBOARDING, or a panel member (V132) — checked in the service.
        Resource resource = candidateService.downloadCvForViewer(id);
        String filename  = resource.getFilename() != null ? resource.getFilename() : "cv";
        // Strip UUID prefix (uuid_originalname.pdf → originalname.pdf)
        String displayName = filename.contains("_") ? filename.substring(filename.indexOf('_') + 1) : filename;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(displayName).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/{id}/hire")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public HireCandidateResponse hire(@PathVariable Long id,
                                      @Valid @RequestBody HireCandidateRequest request,
                                      Authentication auth) {
        return candidateService.hireCandidate(id, request, actorId(auth));
    }

    // ── Offer / negotiation stage ───────────────────────────────────────────
    /** The candidate's CURRENT offer round (404 if none was ever drafted). */
    @GetMapping("/{id}/offer")
    @PreAuthorize(OFFER_READERS)
    public OfferResponse getOffer(@PathVariable Long id) {
        return offerService.getByCandidate(id);
    }

    /**
     * The two offer reads had no guard at all. Harmless while only candidate staff could reach
     * a candidate, but V132 lets an interviewer with no RH code open the record — and an offer
     * round is salary. Whoever could open the candidate page before keeps reading it.
     */
    private static final String OFFER_READERS =
            "hasAnyAuthority('VIEW_CANDIDATES','HR_ONBOARDING','EDIT_CANDIDATE','CREATE_CANDIDATE',"
            + "'IT_PROVISIONING','RH_HIRE_CANDIDATE','APPROVE_HIRING_COST')";

    /** Every round, newest first — the negotiation history (V98). */
    @GetMapping("/{id}/offer/rounds")
    @PreAuthorize(OFFER_READERS)
    public List<OfferResponse> getOfferRounds(@PathVariable Long id) {
        return offerService.getRounds(id);
    }

    /**
     * Draft an offer round — the first one, or a renegotiation superseding the current one.
     *
     * <p>Sends nothing to the candidate: the round is created DRAFT and goes to the finance
     * approval queue. Was `sendOffer`, and the rename is the point — an offer is now costed
     * and approved before anyone hears about it.
     */
    @PostMapping("/{id}/offer")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public OfferResponse draftOffer(@PathVariable Long id,
                                    @Valid @RequestBody CreateOfferRequest request,
                                    Authentication auth) {
        return offerService.draftOffer(id, request, actorId(auth));
    }

    /**
     * Renegotiate — same thing as drafting, since a revision IS a new round. Kept as PUT so
     * the existing frontend call site stays valid.
     */
    @PutMapping("/{id}/offer")
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public OfferResponse renegotiateOffer(@PathVariable Long id,
                                          @Valid @RequestBody CreateOfferRequest request,
                                          Authentication auth) {
        return offerService.draftOffer(id, request, actorId(auth));
    }

    /** The offer email as it would leave now — the entity's template, rendered (V134). */
    @GetMapping("/{id}/offer/email-preview")
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public CandidateEmailDtos.EmailPreviewDto previewOfferEmail(@PathVariable Long id) {
        return offerService.previewOfferEmail(id);
    }

    /**
     * Extend the approved DRAFT round to the candidate → candidate OFFER_SENT, offer emailed.
     * The body carries RH's edited subject / body; absent (older clients) ⇒ the template.
     */
    @PostMapping("/{id}/offer/send")
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public OfferResponse sendOffer(@PathVariable Long id,
                                   @Valid @RequestBody(required = false) CandidateEmailDtos.SendOfferRequest request,
                                   Authentication auth) {
        return offerService.sendOffer(id, actorId(auth),
                request != null ? request.emailSubject() : null,
                request != null ? request.emailBody() : null);
    }

    /** Candidate accepts the offer → begins IT provisioning. */
    @PostMapping("/{id}/offer/accept")
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public OfferResponse acceptOffer(@PathVariable Long id, Authentication auth) {
        return offerService.acceptOffer(id, actorId(auth));
    }

    /** Candidate declines (or RH withdraws) the offer → candidate REJECTED. */
    @PostMapping("/{id}/offer/reject")
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public OfferResponse rejectOffer(@PathVariable Long id,
                                     @Valid @RequestBody RejectOfferRequest request,
                                     Authentication auth) {
        return offerService.rejectOffer(id, request, actorId(auth));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyAuthority('VIEW_CANDIDATES','HR_ONBOARDING','IT_PROVISIONING','HR_UPDATE_PROFILE','ADMIN_ROLES')")
    public ResponseEntity<List<CandidateHistoryItem>> getHistory(
            @PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(candidateService.getHistory(id));
    }

    /** JWT sub = String.valueOf(userId) — see portal JwtTokenService. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return 1L;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
