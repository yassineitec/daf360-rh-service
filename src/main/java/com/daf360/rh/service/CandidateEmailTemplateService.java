package com.daf360.rh.service;

import com.daf360.rh.domain.CandidateEmailTemplate;
import com.daf360.rh.dto.email.CandidateEmailDtos.DefaultTemplateDto;
import com.daf360.rh.dto.email.CandidateEmailDtos.SaveTemplateRequest;
import com.daf360.rh.dto.email.CandidateEmailDtos.TemplateDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateEmailTemplateRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emails sent to candidates (V134) — the templates edited in « Administration > Modèles
 * d'emails candidats », and their rendering.
 *
 * <p>Same model as the finance WIP email templates: plain-text subject and body with
 * {@code {{tokens}}}, one template per code and entity, a built-in default when none is
 * configured. RH always sees the rendered email before it goes and may edit that one send.
 *
 * <p>Gated by {@code RH_ADMIN_INTERVIEW_TYPES}: it is recruitment configuration, held by the
 * same people, and a new code would mean a grant and a re-login for nothing.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CandidateEmailTemplateService {

    private static final String ADMIN = "hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')";

    public static final Set<String> CODES = Set.of(
            CandidateEmailTemplate.CODE_OFFER, CandidateEmailTemplate.CODE_OFFER_REVISED);

    /** Tokens of the offer emails — shown in the editor, replaced at render time. */
    public static final List<String> OFFER_TOKENS = List.of(
            "{{candidate_first_name}}", "{{candidate_last_name}}", "{{position}}",
            "{{salary}}", "{{currency}}", "{{hire_date}}", "{{notice_days}}",
            "{{expiry_date}}", "{{note}}", "{{round}}");

    private static final Map<String, String[]> DEFAULTS = Map.of(
            CandidateEmailTemplate.CODE_OFFER, new String[]{
                    "Proposition d'embauche — {{position}}",
                    """
                    Bonjour {{candidate_first_name}},

                    Nous avons le plaisir de vous adresser notre proposition d'embauche pour le poste de {{position}} :

                    Salaire net mensuel : {{salary}} {{currency}}
                    Date d'entrée prévue : {{hire_date}}
                    Préavis : {{notice_days}} jours
                    Offre valable jusqu'au : {{expiry_date}}

                    Merci de nous faire part de votre réponse — acceptation, refus ou contre-proposition.

                    Cordialement,
                    L'équipe Ressources Humaines"""},
            CandidateEmailTemplate.CODE_OFFER_REVISED, new String[]{
                    "Nouvelle proposition d'embauche — {{position}}",
                    """
                    Bonjour {{candidate_first_name}},

                    Suite à nos échanges, nous avons le plaisir de vous adresser une nouvelle proposition pour le poste de {{position}} :

                    Salaire net mensuel : {{salary}} {{currency}}
                    Date d'entrée prévue : {{hire_date}}
                    Préavis : {{notice_days}} jours
                    Offre valable jusqu'au : {{expiry_date}}

                    Merci de nous faire part de votre réponse — acceptation, refus ou contre-proposition.

                    Cordialement,
                    L'équipe Ressources Humaines"""});

    private final CandidateEmailTemplateRepository repo;
    private final TenantService tenantService;

    // ── Admin ────────────────────────────────────────────────────────────────

    @PreAuthorize(ADMIN)
    @Transactional(readOnly = true)
    public List<TemplateDto> list(Long paysId) {
        assertInScope(paysId);
        return repo.findByPaysIdOrderByCodeAsc(paysId).stream().map(TemplateDto::from).toList();
    }

    @PreAuthorize(ADMIN)
    public List<DefaultTemplateDto> defaults() {
        return DEFAULTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new DefaultTemplateDto(e.getKey(), e.getValue()[0], e.getValue()[1], OFFER_TOKENS))
                .toList();
    }

    @PreAuthorize(ADMIN)
    public TemplateDto create(SaveTemplateRequest req, Long actorId) {
        assertCode(req.code());
        assertInScope(req.paysId());
        if (repo.findByCodeAndPaysId(req.code(), req.paysId()).isPresent()) {
            throw new BusinessRuleException("Un modèle existe déjà pour cet email dans cette entité : modifiez-le.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        CandidateEmailTemplate t = CandidateEmailTemplate.builder()
                .code(req.code())
                .paysId(req.paysId())
                .subject(req.subject().trim())
                .body(req.body())
                .isActive(req.active() == null || req.active())
                .updatedBy(actorId)
                .createdAt(now)
                .build();
        return TemplateDto.from(repo.save(t));
    }

    @PreAuthorize(ADMIN)
    public TemplateDto update(Long id, SaveTemplateRequest req, Long actorId) {
        CandidateEmailTemplate t = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateEmailTemplate", id));
        assertInScope(t.getPaysId());
        assertCode(req.code());
        if (!t.getCode().equals(req.code()) || !t.getPaysId().equals(req.paysId())) {
            repo.findByCodeAndPaysId(req.code(), req.paysId()).ifPresent(other -> {
                throw new BusinessRuleException("Un modèle existe déjà pour cet email dans cette entité.");
            });
            assertInScope(req.paysId());
        }
        t.setCode(req.code());
        t.setPaysId(req.paysId());
        t.setSubject(req.subject().trim());
        t.setBody(req.body());
        t.setIsActive(req.active() == null || req.active());
        t.setUpdatedBy(actorId);
        t.setUpdatedAt(OffsetDateTime.now());
        return TemplateDto.from(repo.save(t));
    }

    @PreAuthorize(ADMIN)
    public void delete(Long id) {
        CandidateEmailTemplate t = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateEmailTemplate", id));
        assertInScope(t.getPaysId());
        repo.delete(t);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Subject and body for {@code code} in the entity: its active template, else the built-in
     * default — with every token replaced (an unknown value renders empty).
     */
    @Transactional(readOnly = true)
    public String[] render(String code, Long paysId, Map<String, String> tokens) {
        String[] source = repo.findByCodeAndPaysIdAndIsActiveTrue(code, paysId)
                .map(t -> new String[]{t.getSubject(), t.getBody()})
                .orElseGet(() -> DEFAULTS.get(code));
        return new String[]{apply(source[0], tokens), apply(source[1], tokens)};
    }

    private static String apply(String template, Map<String, String> tokens) {
        String out = template;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return out;
    }

    private static void assertCode(String code) {
        if (!CODES.contains(code)) {
            throw new BusinessRuleException("Type d'email inconnu : " + code);
        }
    }

    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.unfiltered()) return;
        if (paysId == null || !scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette entité n'est pas dans votre périmètre.");
        }
    }
}
