package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.CdiConversionRequest;
import com.daf360.rh.domain.CdiConversionStep;
import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeDocument;
import com.daf360.rh.domain.enums.CdiConversionStatus;
import com.daf360.rh.dto.lifecycle.CdiConversionDecisionRequest;
import com.daf360.rh.dto.lifecycle.CdiConversionDto;
import com.daf360.rh.dto.lifecycle.SubmitCdiConversionRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CdiConversionRequestRepository;
import com.daf360.rh.repository.CdiConversionStepRepository;
import com.daf360.rh.repository.EmployeeContractRepository;
import com.daf360.rh.repository.EmployeeDocumentRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.EmployeeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CIVP → CDI conversion requests (workshop "Contrats et validation").
 *
 * <p>A CIVP resource works for several managers during its integration, so the move to CDI
 * is not one manager's call: anyone holding {@link #REQUEST} files it with a proposed start
 * date and net salary; the technical &amp; salary validator ({@link #VALIDATE}) approves,
 * rejects or escalates; an escalated request is decided by the arbiter ({@link #ARBITRATE});
 * RH ({@link #HR}) is notified of the final decision and carries out the conversion from the
 * profile ({@link EmployeeLifecycleService#convertToCDI}), which consumes the approved
 * request. Roles are modelled as permissions granted in Administration › Rôles — never as
 * names.
 *
 * <p>Four-eyes: nobody decides a request they filed, the arbiter is not the validator who
 * escalated, and nobody files or decides their own conversion. {@code app.cdi-conversion.four-eyes}
 * (default true) switches the first two off for a single-account test environment only.
 *
 * <p>Evaluation file (V120): a request is filed WITH the CIVP's evaluation, so the validator
 * decides on evidence. It is an ordinary employee document of type {@link #EVALUATION_DOC_TYPE}
 * — stored locally, mirrored to the employee's SharePoint folder, kept in the dossier after the
 * request closes — linked by id, the same way a leave request links its justificatif (V110).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CdiConversionService {

    public static final String REQUEST   = "RH_REQUEST_CDI_CONVERSION";
    public static final String VALIDATE  = "RH_VALIDATE_CDI_CONVERSION";
    public static final String ARBITRATE = "RH_ARBITRATE_CDI_CONVERSION";
    public static final String HR        = "RH_MANAGE_LIFECYCLE";
    private static final String VIEW_CONTRACTS = "RH_VIEW_CONTRACTS";

    /** The {@code document_types} code every evaluation file is filed under (V120). */
    public static final String EVALUATION_DOC_TYPE = "EVALUATION_CIVP";

    private static final String ENTITY = "CDI_CONVERSION_REQUEST";
    private static final List<CdiConversionStatus> OPEN =
            List.of(CdiConversionStatus.SUBMITTED, CdiConversionStatus.ESCALATED, CdiConversionStatus.APPROVED);
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CdiConversionRequestRepository requestRepo;
    private final CdiConversionStepRepository    stepRepo;
    private final EmployeeContractRepository     contractRepo;
    private final ContractTypeRefs               contractTypeRefs;
    private final NotificationRoutingService     notificationRoutingService;
    private final TenantService                  tenantService;
    private final AuditService                   auditService;
    private final JdbcTemplate                   jdbc;
    private final EmployeeDocumentService        documents;
    private final EmployeeDocumentRepository     documentRepo;

    /** Requester ≠ decider and validator ≠ arbiter. Off only for a single-account test base. */
    @Value("${app.cdi-conversion.four-eyes:true}")
    private boolean fourEyes = true;

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Files the request with its evaluation file. Every rule is checked BEFORE the file is
     * stored, so a refused request leaves no orphan document in the dossier.
     */
    public CdiConversionDto submit(Long contractId, SubmitCdiConversionRequest dto, MultipartFile evaluation,
                                   Authentication auth, Long actor) throws IOException {
        require(REQUEST);
        EmployeeContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new BusinessRuleException("CDI-01", "Contrat introuvable."));
        checkPays(contract.getPaysId());

        if (!"CIVP".equals(contractTypeRefs.natureOf(contract.getContractTypeId(), contract.getPaysId()))) {
            throw new BusinessRuleException("CDI-01", "Seul un contrat CIVP peut faire l'objet d'une demande de passage en CDI.");
        }
        if (!Boolean.TRUE.equals(contract.getIsActive()) || !"ACTIF".equals(contract.getCurrentStatusCode())) {
            throw new BusinessRuleException("CDI-01",
                    "Le contrat CIVP doit être actif (période d'essai validée) pour demander le passage en CDI.");
        }
        if (!requestRepo.findByContractIdAndStatusIn(contractId, OPEN).stream()
                .filter(r -> r.getStatus().isPending() || r.isAwaitingConversion()).toList().isEmpty()) {
            throw new BusinessRuleException("CDI-02", "Une demande de passage en CDI est déjà en cours pour ce contrat.");
        }
        Long employeeUserId = contract.getEmployeeProfile().getUserId();
        if (actor != null && actor.equals(employeeUserId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez pas demander votre propre passage en CDI.");
        }
        if (contract.getDateDebut() != null && dto.getRequestedStartDate().isBefore(contract.getDateDebut())) {
            throw new BusinessRuleException("CDI-03", "La date de début du CDI ne peut pas précéder le début du CIVP.");
        }
        if (evaluation == null || evaluation.isEmpty()) {
            throw new BusinessRuleException("CDI-08", "Le fichier d'évaluation du CIVP est obligatoire.");
        }

        // Type, MIME (PDF / JPEG / PNG) and the 10 MB cap are enforced by the document service.
        Long evaluationDocId = documents.upload(contract.getEmployeeProfile().getId(), evaluation,
                EVALUATION_DOC_TYPE, null, "Évaluation — demande de passage en CDI", auth).getId();

        CdiConversionRequest req = requestRepo.save(CdiConversionRequest.builder()
                .contractId(contractId)
                .employeeProfileId(contract.getEmployeeProfile().getId())
                .paysId(contract.getPaysId())
                .status(CdiConversionStatus.SUBMITTED)
                .requestedBy(actor)
                .requestedStartDate(dto.getRequestedStartDate())
                .proposedNetSalary(scale(dto.getProposedNetSalary()))
                .justification(dto.getJustification().trim())
                .evaluationDocumentId(evaluationDocId)
                .build());
        step(req, "SUBMIT", actor, req.getJustification(), req.getProposedNetSalary(), req.getRequestedStartDate());
        auditService.log(String.valueOf(actor), "CREATE", ENTITY, req.getId(), null,
                "contract=" + contractId + ", salary=" + req.getProposedNetSalary());
        dispatch("CDI_CONVERSION_SUBMITTED", req, actor, req.getJustification());
        return toDto(req, new Names());
    }

    // ── Decide (validator / arbiter) ──────────────────────────────────────────

    public CdiConversionDto decide(Long id, CdiConversionDecisionRequest dto, Long actor) {
        CdiConversionRequest req = find(id);
        checkPays(req.getPaysId());
        CdiConversionStatus from = req.getStatus();

        if (from == CdiConversionStatus.SUBMITTED) {
            require(VALIDATE);
        } else if (from == CdiConversionStatus.ESCALATED) {
            require(ARBITRATE);
            if (dto.getAction() == CdiConversionDecisionRequest.Action.ESCALATE) {
                throw new BusinessRuleException("CDI-04", "Une demande déjà escaladée ne peut pas l'être à nouveau.");
            }
            if (fourEyes && actor != null && actor.equals(req.getEscalatedBy())) {
                throw new AppException(ErrorCode.FORBIDDEN,
                        "Vous avez escaladé cette demande : l'arbitrage revient à une autre personne.");
            }
        } else {
            throw new BusinessRuleException("CDI-04", "Cette demande n'est plus en attente de décision.");
        }
        if (fourEyes && actor != null && actor.equals(req.getRequestedBy())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez pas décider d'une demande que vous avez déposée.");
        }
        if (actor != null && actor.equals(employeeUserId(req))) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez pas décider de votre propre passage en CDI.");
        }
        String comment = dto.getComment() == null || dto.getComment().isBlank() ? null : dto.getComment().trim();
        if (dto.getAction() != CdiConversionDecisionRequest.Action.APPROVE && comment == null) {
            throw new BusinessRuleException("CDI-05", "Un commentaire est obligatoire pour refuser ou escalader.");
        }

        switch (dto.getAction()) {
            case ESCALATE -> {
                req.setStatus(CdiConversionStatus.ESCALATED);
                req.setEscalatedBy(actor);
                step(req, "ESCALATE", actor, comment, null, null);
                dispatch("CDI_CONVERSION_ESCALATED", req, actor, comment);
            }
            case APPROVE -> {
                LocalDate start = dto.getStartDate() != null ? dto.getStartDate() : req.getRequestedStartDate();
                BigDecimal salary = dto.getNetSalary() != null ? scale(dto.getNetSalary()) : req.getProposedNetSalary();
                req.setStatus(CdiConversionStatus.APPROVED);
                req.setValidatedStartDate(start);
                req.setValidatedNetSalary(salary);
                req.setDecidedBy(actor);
                req.setDecidedAt(OffsetDateTime.now());
                step(req, "APPROVE", actor, comment, salary, start);
                dispatch("CDI_CONVERSION_APPROVED", req, actor, comment);
            }
            case REJECT -> {
                req.setStatus(CdiConversionStatus.REJECTED);
                req.setDecidedBy(actor);
                req.setDecidedAt(OffsetDateTime.now());
                step(req, "REJECT", actor, comment, null, null);
                dispatch("CDI_CONVERSION_REJECTED", req, actor, comment);
            }
        }
        req = requestRepo.save(req);
        auditService.log(String.valueOf(actor), dto.getAction().name(), ENTITY, req.getId(),
                "status=" + from, "status=" + req.getStatus());
        return toDto(req, new Names());
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    public CdiConversionDto cancel(Long id, String comment, Long actor) {
        CdiConversionRequest req = find(id);
        checkPays(req.getPaysId());
        if (!req.getStatus().isPending()) {
            throw new BusinessRuleException("CDI-06", "Seule une demande en attente peut être annulée.");
        }
        if (!(actor != null && actor.equals(req.getRequestedBy())) && !has(HR)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Seul le demandeur ou les RH peuvent annuler cette demande.");
        }
        CdiConversionStatus from = req.getStatus();
        req.setStatus(CdiConversionStatus.CANCELLED);
        req = requestRepo.save(req);
        String c = comment == null || comment.isBlank() ? null : comment.trim();
        step(req, "CANCEL", actor, c, null, null);
        auditService.log(String.valueOf(actor), "CANCEL", ENTITY, req.getId(), "status=" + from, "status=CANCELLED");
        dispatch("CDI_CONVERSION_CANCELLED", req, actor, c);
        return toDto(req, new Names());
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * {@code todo}: what waits for the caller — SUBMITTED for validators, ESCALATED for
     * arbiters, APPROVED-not-converted for RH (four-eyes applied). Otherwise every request of
     * the caller's pays scope; a pure requester sees only their own.
     */
    @Transactional(readOnly = true)
    public List<CdiConversionDto> list(boolean todo, Long actor) {
        requireAny(REQUEST, VALIDATE, ARBITRATE, HR, VIEW_CONTRACTS);
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        List<CdiConversionRequest> rows = scope.unfiltered()
                ? requestRepo.findAllByOrderByCreatedAtDescIdDesc()
                : requestRepo.findByPaysIdInOrderByCreatedAtDescIdDesc(scope.paysIds());
        boolean seesAll = has(VALIDATE) || has(ARBITRATE) || has(HR) || has(VIEW_CONTRACTS);
        Names names = new Names();
        return rows.stream()
                .filter(r -> seesAll || Objects.equals(r.getRequestedBy(), actor))
                .map(r -> toDto(r, names))
                .filter(d -> !todo || d.canDecide() || d.canConvert())
                .toList();
    }

    @Transactional(readOnly = true)
    public CdiConversionDto get(Long id, Long actor) {
        requireAny(REQUEST, VALIDATE, ARBITRATE, HR, VIEW_CONTRACTS);
        CdiConversionRequest req = find(id);
        checkPays(req.getPaysId());
        boolean seesAll = has(VALIDATE) || has(ARBITRATE) || has(HR) || has(VIEW_CONTRACTS);
        if (!seesAll && !Objects.equals(req.getRequestedBy(), actor)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Accès refusé à cette demande.");
        }
        return toDto(req, new Names());
    }

    /**
     * The evaluation file, for download — readable by whoever may read the request: the
     * validator has to open the evaluation they are asked to decide on. Empty when nothing is
     * attached (pre-V120) or the document was since deleted: "no file", never a 500.
     */
    @Transactional(readOnly = true)
    public Optional<EmployeeDocumentService.DownloadPayload> evaluation(Long id, Long actor) {
        get(id, actor);   // same permission, pays and requester checks as reading the request
        CdiConversionRequest req = find(id);
        if (req.getEvaluationDocumentId() == null) return Optional.empty();
        try {
            return Optional.of(documents.download(req.getEmployeeProfileId(), req.getEvaluationDocumentId()));
        } catch (AppException e) {
            log.info("CDI request {} points at evaluation document {}, which is gone: {}",
                    id, req.getEvaluationDocumentId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Latest request of a contract (profile contract card), empty when there is none. */
    @Transactional(readOnly = true)
    public Optional<CdiConversionDto> latestForContract(Long contractId) {
        requireAny(REQUEST, VALIDATE, ARBITRATE, HR, VIEW_CONTRACTS);
        return requestRepo.findFirstByContractIdOrderByCreatedAtDescIdDesc(contractId)
                .map(r -> toDto(r, new Names()));
    }

    // ── Hooks for EmployeeLifecycleService.convertToCDI ───────────────────────

    /** The APPROVED, not yet converted request a CIVP conversion must rest on. */
    CdiConversionRequest requireApproved(Long contractId) {
        return requestRepo.findByContractIdAndStatusIn(contractId, List.of(CdiConversionStatus.APPROVED)).stream()
                .filter(CdiConversionRequest::isAwaitingConversion)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("CDI-07",
                        "Le passage en CDI d'un CIVP nécessite une demande validée (technique et salariale)."));
    }

    void markConverted(CdiConversionRequest req, Long cdiContractId, LocalDate startDate, Long actor) {
        req.setConvertedContractId(cdiContractId);
        req.setConvertedAt(OffsetDateTime.now());
        requestRepo.save(req);
        step(req, "CONVERT", actor, null, req.getValidatedNetSalary(), startDate);
        auditService.log(String.valueOf(actor), "CONVERT", ENTITY, req.getId(), null, "cdiContract=" + cdiContractId);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private CdiConversionDto toDto(CdiConversionRequest r, Names names) {
        Long me = currentUserId();
        boolean notMine = !fourEyes || me == null || !me.equals(r.getRequestedBy());
        boolean notEmployee = me == null || !me.equals(employeeUserId(r));
        boolean canDecide = notMine && notEmployee && (
                (r.getStatus() == CdiConversionStatus.SUBMITTED && has(VALIDATE))
                || (r.getStatus() == CdiConversionStatus.ESCALATED && has(ARBITRATE)
                    && (!fourEyes || me == null || !me.equals(r.getEscalatedBy()))));
        boolean canEscalate = canDecide && r.getStatus() == CdiConversionStatus.SUBMITTED;
        boolean canCancel = r.getStatus().isPending() && ((me != null && me.equals(r.getRequestedBy())) || has(HR));
        boolean canConvert = r.isAwaitingConversion() && has(HR);

        Optional<EmployeeContract> contract = contractRepo.findById(r.getContractId());
        List<CdiConversionDto.Step> steps = stepRepo.findByRequestIdOrderByCreatedAtAscIdAsc(r.getId()).stream()
                .map(s -> new CdiConversionDto.Step(s.getId(), s.getAction(), s.getActorUserId(),
                        names.user(s.getActorUserId()), s.getComment(), s.getNetSalary(), s.getStartDate(), s.getCreatedAt()))
                .toList();
        // A soft-deleted document reads as "no file", like a pre-V120 request.
        Optional<EmployeeDocument> evaluation = Optional.ofNullable(r.getEvaluationDocumentId())
                .flatMap(documentRepo::findById)
                .filter(d -> !Boolean.TRUE.equals(d.getIsDeleted()));
        return new CdiConversionDto(r.getId(), r.getContractId(), r.getEmployeeProfileId(),
                names.employee(r.getEmployeeProfileId()), r.getPaysId(), r.getStatus().name(),
                r.getRequestedBy(), names.user(r.getRequestedBy()), r.getCreatedAt(),
                r.getRequestedStartDate(), r.getProposedNetSalary(), r.getJustification(),
                evaluation.map(EmployeeDocument::getId).orElse(null),
                evaluation.map(EmployeeDocument::getFileName).orElse(null),
                r.getValidatedNetSalary(), r.getValidatedStartDate(),
                r.getDecidedBy(), names.user(r.getDecidedBy()), r.getDecidedAt(),
                r.getConvertedContractId(), r.getConvertedAt(),
                contract.map(EmployeeContract::getDateDebut).orElse(null),
                contract.map(EmployeeContract::getDateFinPrevue).orElse(null),
                canDecide, canEscalate, canCancel, canConvert, steps);
    }

    /** Per-call name cache: a list reads the same requester / validator many times. */
    private final class Names {
        private final Map<Long, String> users = new HashMap<>();
        private final Map<Long, String> employees = new HashMap<>();

        String user(Long id) {
            return id == null ? null : users.computeIfAbsent(id, CdiConversionService.this::userName);
        }

        String employee(Long profileId) {
            return profileId == null ? null : employees.computeIfAbsent(profileId, CdiConversionService.this::employeeName);
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /**
     * Audiences live in V119's routing rules: SUBMITTED → validators, ESCALATED → arbiters,
     * APPROVED / REJECTED → RH + the requester, CANCELLED → validators (+ arbiters).
     */
    private void dispatch(String eventCode, CdiConversionRequest r, Long actor, String comment) {
        Map<String, String> vars = new HashMap<>();
        vars.put("employeeName",  nz(employeeName(r.getEmployeeProfileId())));
        vars.put("requesterName", nz(userName(r.getRequestedBy())));
        vars.put("actorName",     nz(userName(actor)));
        vars.put("startDate",     date(r.getValidatedStartDate() != null ? r.getValidatedStartDate() : r.getRequestedStartDate()));
        vars.put("salary",        amount(r.getValidatedNetSalary() != null ? r.getValidatedNetSalary() : r.getProposedNetSalary()));
        vars.put("comment",       nz(comment));
        notificationRoutingService.dispatchAfterCommit(RoutingContext.builder()
                .eventCode(eventCode)
                .paysId(r.getPaysId())
                .subjectUserId(r.getRequestedBy())
                .actorUserId(actor)
                .entityType(NotificationEntityType.CDI_CONVERSION)
                .entityId(r.getId())
                .templateVars(vars)
                .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CdiConversionRequest find(Long id) {
        return requestRepo.findById(id)
                .orElseThrow(() -> new BusinessRuleException("CDI-00", "Demande de passage en CDI introuvable."));
    }

    private void step(CdiConversionRequest r, String action, Long actor, String comment, BigDecimal salary, LocalDate start) {
        stepRepo.save(CdiConversionStep.builder()
                .requestId(r.getId()).action(action).actorUserId(actor)
                .comment(comment).netSalary(salary).startDate(start).build());
    }

    private Long employeeUserId(CdiConversionRequest r) {
        return contractRepo.findById(r.getContractId())
                .map(c -> c.getEmployeeProfile().getUserId()).orElse(null);
    }

    private void checkPays(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (!scope.unfiltered() && !scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Ce contrat n'appartient pas à votre périmètre pays.");
        }
    }

    private void require(String permission) {
        if (!has(permission)) throw new AppException(ErrorCode.FORBIDDEN, "Permission requise : " + permission);
    }

    private void requireAny(String... permissions) {
        for (String p : permissions) if (has(p)) return;
        throw new AppException(ErrorCode.FORBIDDEN, "Accès refusé aux demandes de passage en CDI.");
    }

    boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String userName(Long userId) {
        if (userId == null) return null;
        List<String> n = jdbc.queryForList(
                "SELECT COALESCE(fullName, username, email) FROM [dbo].[Users] WHERE id = ?", String.class, userId);
        return n.isEmpty() ? null : n.get(0);
    }

    private String employeeName(Long profileId) {
        if (profileId == null) return null;
        List<String> n = jdbc.queryForList(
                "SELECT COALESCE(u.fullName, u.username, u.email, 'Collaborateur') FROM [dbo].[Users] u "
                        + "JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id WHERE ep.id = ?", String.class, profileId);
        return n.isEmpty() ? null : n.get(0);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(3, RoundingMode.HALF_UP);
    }

    private static String date(LocalDate d) {
        return d == null ? "" : d.format(FR_DATE);
    }

    private static String amount(BigDecimal v) {
        return v == null ? "" : v.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
