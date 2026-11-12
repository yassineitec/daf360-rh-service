package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.lists.ConfigurableListTypeRepository;
import com.daf360.rh.lists.ConfigurableListValue;
import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.repository.EmployeeContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.daf360.rh.lifecycle.ContractDecisionAction.Moment;

/**
 * « Décider » on a contract deadline: which choices the contract offers now, and carrying
 * one out.
 *
 * <p>Three layers, each owned by a different screen:
 * <ul>
 *   <li>the choice's NAME — list CONTRACT_DECISION (Administration › Listes configurables),
 *       whose {@code decision_action} column holds the action;</li>
 *   <li>which choices a contract OFFERS, and its limits — its contract type's own rules
 *       (Listes configurables › Type de contrat), else its nature's contract_type_config row
 *       (Administration › Échéances de contrat): see {@link ContractRules};</li>
 *   <li>what a choice DOES — {@link ContractDecisionAction}, carried out by the existing
 *       lifecycle operations (validate trial, renew CDD, transition…) so their rules and
 *       permissions still apply.</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ContractDecisionService {

    public static final String LIST_CODE = "CONTRACT_DECISION";

    private final EmployeeContractRepository      contractRepo;
    private final ConfigurableListTypeRepository  listTypeRepo;
    private final ConfigurableListValueRepository listValueRepo;
    private final ContractTypeRefs                contractTypeRefs;
    private final LifecycleStateMachine           stateMachine;
    /** Through the proxy: each operation keeps its own @PreAuthorize. */
    private final EmployeeLifecycleService        lifecycleService;

    // ── What can be decided now ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ContractDecisionOptionsDto getOptions(Long contractId) {
        EmployeeContract c = load(contractId);
        Moment moment = momentOf(c);
        if (moment == null) {
            return ContractDecisionOptionsDto.builder().options(List.of()).build();
        }
        String nature = nature(c);
        ContractRules rules = lifecycleService.rulesOf(c);
        List<ContractDecisionAction> offered = rules.decisionsAt(moment);

        List<ContractDecisionOptionsDto.Option> options = new ArrayList<>();
        for (ConfigurableListValue v : decisionValues(c.getPaysId())) {
            ContractDecisionAction a = ContractDecisionAction.parse(v.getDecisionAction()).orElse(null);
            if (a == null || a.moment() != moment || !offered.contains(a)) continue;
            options.add(ContractDecisionOptionsDto.Option.builder()
                .id(v.getId())
                .action(a.name())
                .labelFr(v.getLabelFr())
                .labelEn(v.getLabelEn())
                .requiresDate(a.requiresDate())
                .blockedReason(blockedReason(c, nature, rules, a))
                .build());
        }
        return ContractDecisionOptionsDto.builder()
            .moment(moment.name())
            .deadline(moment == Moment.TRIAL_END ? LifecycleAlertJob.effectiveTrialEnd(c) : c.getDateFinPrevue())
            .options(options)
            .build();
    }

    // ── Carry one out ─────────────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_TRIAL_PERIOD') or hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto decide(Long contractId, ContractDecisionRequest dto, Long actorId) {
        EmployeeContract c = load(contractId);
        ContractDecisionOptionsDto.Option option = getOptions(contractId).getOptions().stream()
            .filter(o -> Objects.equals(o.getId(), dto.getDecisionId()))
            .findFirst()
            .orElseThrow(() -> new BusinessRuleException("D3-96",
                "Ce choix n'est pas proposé pour ce contrat à ce stade."));
        if (option.getBlockedReason() != null) {
            throw new BusinessRuleException("D3-96", option.getBlockedReason());
        }
        ContractDecisionAction action = ContractDecisionAction.valueOf(option.getAction());
        if (action.requiresDate() && dto.getNewDate() == null) {
            throw new BusinessRuleException("D3-96", "La nouvelle date de fin est obligatoire pour ce choix.");
        }
        // The admin's wording is what HR picked: keep it in the log next to the free comment.
        String comment = "« " + option.getLabelFr() + " »"
            + (dto.getCommentaire() != null && !dto.getCommentaire().isBlank() ? " — " + dto.getCommentaire().trim() : "");

        return switch (action) {
            case CONFIRM_TRIAL, BREAK_TRIAL -> {
                ValidateTrialRequest req = new ValidateTrialRequest();
                req.setApproved(action == ContractDecisionAction.CONFIRM_TRIAL);
                req.setCommentaire(comment);
                yield lifecycleService.validateTrialPeriod(contractId, req, actorId);
            }
            case RENEW_TRIAL -> lifecycleService.renewTrialPeriod(contractId, dto.getNewDate(), comment, actorId);
            case RENEW_CDD -> {
                RenewCDDRequest req = new RenewCDDRequest();
                req.setNewDateFin(dto.getNewDate());
                req.setCommentaire(comment);
                yield lifecycleService.renewCDD(contractId, req, actorId);
            }
            case EXTEND -> lifecycleService.extendContract(contractId, dto.getNewDate(), comment, actorId);
            case END_CONTRACT -> lifecycleService.transitionState(contractId,
                TransitionRequest.builder()
                    .newStatus(endStatus(nature(c)))
                    .actionCode(ContractDecisionAction.END_CONTRACT.name())
                    .commentaire(comment)
                    .build(),
                actorId);
        };
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    /**
     * Which deadline a contract is at. TRIAL_END while in trial; CONTRACT_END for an active
     * contract that has (or, for a non-CDI, may have) an end; null when nothing is to decide.
     */
    private Moment momentOf(EmployeeContract c) {
        if (!Boolean.TRUE.equals(c.getIsActive()) || Boolean.TRUE.equals(c.getDossierLocked())) return null;
        String status = c.getCurrentStatusCode();
        if ("PERIODE_ESSAI".equals(status)) return Moment.TRIAL_END;
        if ("RECRUTEMENT".equals(status)) return null;
        if (c.getDateFinPrevue() != null || !"CDI".equals(nature(c))) return Moment.CONTRACT_END;
        return null;
    }

    /** Null when the action can be taken now; else the reason, in words HR can act on. */
    private String blockedReason(EmployeeContract c, String nature, ContractRules rules,
                                 ContractDecisionAction a) {
        LocalDate today = LocalDate.now();
        return switch (a) {
            case CONFIRM_TRIAL, BREAK_TRIAL -> null;
            case RENEW_TRIAL -> {
                if (!rules.trialRenewable()) {
                    yield "La période d'essai de ce type de contrat n'est pas renouvelable ("
                        + (rules.inherited() ? "Administration › Échéances de contrat" : "Listes configurables › Type de contrat") + ").";
                }
                long done = lifecycleService.trialRenewalCount(c.getId());
                int max = rules.trialMaxRenewals();
                if (done >= max) yield "Période d'essai déjà renouvelée " + done + " fois (maximum : " + max + ").";
                LocalDate end = LifecycleAlertJob.effectiveTrialEnd(c);
                if (end != null && today.isAfter(end)) yield "La période d'essai est terminée depuis le " + end + " : elle ne peut plus être renouvelée.";
                yield null;
            }
            case RENEW_CDD -> {
                if (!"CDD".equals(nature)) yield "Réservé aux CDD.";
                if (c.getCddRenouvellementCount() != null && c.getCddRenouvellementCount() >= 1) yield "Ce CDD a déjà été renouvelé une fois.";
                if (c.getDateFinPrevue() != null && today.isAfter(c.getDateFinPrevue())) yield "Le renouvellement doit être décidé avant le terme du CDD.";
                yield null;
            }
            case EXTEND -> {
                if ("CDD".equals(nature)) yield "Un CDD se renouvelle, il ne se prolonge pas.";
                if (c.getDateFinPrevue() == null) yield "Ce contrat n'a pas de date de fin prévue.";
                yield null;
            }
            case END_CONTRACT -> stateMachine.isTransitionAllowed(nature, c.getCurrentStatusCode(), endStatus(nature))
                ? null
                : "Fin impossible depuis le statut " + c.getCurrentStatusCode() + ".";
        };
    }

    /** The status a contract of this nature ends in. */
    static String endStatus(String nature) {
        return switch (nature) {
            case "STAGE"     -> "FIN_STAGE";
            case "FREELANCE" -> "FIN_MISSION";
            default          -> "FIN_CONTRAT";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EmployeeContract load(Long id) {
        return contractRepo.findById(id)
            .orElseThrow(() -> new BusinessRuleException("D3-96", "Contrat introuvable."));
    }

    private String nature(EmployeeContract c) {
        return contractTypeRefs.natureOf(c.getContractTypeId(), c.getPaysId());
    }

    /** Active CONTRACT_DECISION values of the pays plus the shared ones, in list order. */
    private List<ConfigurableListValue> decisionValues(Long paysId) {
        return listTypeRepo.findByCode(LIST_CODE)
            .map(t -> listValueRepo.findActiveByListTypeAndPays(t.getId(), paysId))
            .orElse(List.of());
    }
}
