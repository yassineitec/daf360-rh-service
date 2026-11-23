package com.daf360.rh.service;

import com.daf360.rh.domain.CandidateInterviewReportAnswer;
import com.daf360.rh.domain.InterviewQuestion;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewQuestionType;
import com.daf360.rh.dto.interview.InterviewAnswerDto;
import com.daf360.rh.dto.interview.InterviewAnswerRequest;
import com.daf360.rh.dto.interview.InterviewQuestionDto;
import com.daf360.rh.dto.interview.SaveInterviewQuestionRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewReportAnswerRepository;
import com.daf360.rh.repository.InterviewQuestionRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Questionnaire of an interview type (V135): the questions every interviewer of that type
 * answers in their PV, so a panel's opinions come back in the same shape and can be compared.
 *
 * <p>Two sides:
 * <ul>
 *   <li>configuration — RH_ADMIN_INTERVIEW_TYPES, same as the types themselves;</li>
 *   <li>answers — no permission here: {@link CandidateInterviewService} has already checked
 *       that the caller sits on the panel (or may read the PVs) before calling in.</li>
 * </ul>
 *
 * <p>A question somebody answered is never deleted, only archived, and its response type and
 * rating scale are frozen: changing them would make the stored answers mean something else.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class InterviewQuestionnaireService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** Hard bounds of a rating scale — a slider, not a free number. */
    private static final int RATING_FLOOR = 0;
    private static final int RATING_CEIL  = 100;
    private static final int MAX_TEXT     = 4000;

    private final InterviewQuestionRepository              questionRepo;
    private final CandidateInterviewReportAnswerRepository answerRepo;
    private final InterviewTypeRepository                  typeRepo;
    private final TenantService                            tenantService;

    // ══ Configuration ═════════════════════════════════════════════════════════

    /** Every question of the type, archived ones included, with their answer counts. */
    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    @Transactional(readOnly = true)
    public List<InterviewQuestionDto> listForAdmin(Long typeId) {
        loadTypeInScope(typeId);
        List<InterviewQuestion> questions = questionRepo.findByInterviewTypeIdOrderByOrderIndexAscIdAsc(typeId);
        Map<Long, Long> counts = answerCounts(questions.stream().map(InterviewQuestion::getId).toList());
        return questions.stream().map(q -> toDto(q, counts.getOrDefault(q.getId(), 0L))).toList();
    }

    /** typeId → number of active questions, for the types table. Types with none are absent. */
    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    @Transactional(readOnly = true)
    public Map<Long, Long> activeCountsByType(Long paysId) {
        assertPaysInScope(paysId);
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : questionRepo.countActiveByTypeForPays(paysId)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewQuestionDto create(Long typeId, SaveInterviewQuestionRequest req) {
        loadTypeInScope(typeId);
        int nextOrder = questionRepo.findByInterviewTypeIdOrderByOrderIndexAscIdAsc(typeId).stream()
                .mapToInt(InterviewQuestion::getOrderIndex).max().orElse(0) + 1;
        InterviewQuestion q = InterviewQuestion.builder()
                .interviewTypeId(typeId)
                .orderIndex(nextOrder)
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build();
        apply(q, req);
        return toDto(questionRepo.save(q), 0L);
    }

    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewQuestionDto update(Long questionId, SaveInterviewQuestionRequest req) {
        InterviewQuestion q = loadQuestionInScope(questionId);
        long answered = answerRepo.countByQuestionId(questionId);
        if (answered > 0) {
            InterviewQuestionType newType = InterviewQuestionType.valueOf(req.responseType());
            if (newType != q.getResponseType()) {
                throw new BusinessRuleException("Le type de réponse ne peut plus changer : " + answered
                        + " PV ont déjà répondu à cette question. Archivez-la et créez-en une nouvelle.");
            }
            if (newType == InterviewQuestionType.RATING) {
                Scale s = scaleOf(req);
                if (s.min() != q.getRatingMin() || s.max() != q.getRatingMax() || s.step() != q.getRatingStep()) {
                    throw new BusinessRuleException("L'échelle de notation ne peut plus changer : " + answered
                            + " PV ont déjà noté cette question. Archivez-la et créez-en une nouvelle.");
                }
            }
        }
        apply(q, req);
        q.setUpdatedAt(OffsetDateTime.now());
        return toDto(questionRepo.save(q), answered);
    }

    /** Archive (no longer asked, answers kept) or bring back. */
    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public InterviewQuestionDto setActive(Long questionId, boolean active) {
        InterviewQuestion q = loadQuestionInScope(questionId);
        q.setIsActive(active);
        q.setUpdatedAt(OffsetDateTime.now());
        return toDto(questionRepo.save(q), answerRepo.countByQuestionId(questionId));
    }

    /** Only a question nobody answered; otherwise it is archived instead. */
    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public void delete(Long questionId) {
        InterviewQuestion q = loadQuestionInScope(questionId);
        long answered = answerRepo.countByQuestionId(questionId);
        if (answered > 0) {
            throw new BusinessRuleException("Cette question a déjà " + answered
                    + " réponse(s) dans des PV : archivez-la plutôt que de la supprimer.");
        }
        questionRepo.delete(q);
    }

    /** Rewrites order_index from the given id list — it must hold exactly the type's questions. */
    @PreAuthorize("hasPermission(null, 'RH_ADMIN_INTERVIEW_TYPES')")
    public List<InterviewQuestionDto> reorder(Long typeId, List<Long> orderedIds) {
        loadTypeInScope(typeId);
        List<InterviewQuestion> questions = questionRepo.findByInterviewTypeIdOrderByOrderIndexAscIdAsc(typeId);
        Set<Long> current = questions.stream().map(InterviewQuestion::getId).collect(Collectors.toSet());
        if (orderedIds.size() != current.size() || !current.equals(new HashSet<>(orderedIds))) {
            throw new BusinessRuleException("La liste des questions a changé entre-temps : rechargez la page.");
        }
        Map<Long, InterviewQuestion> byId = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, q -> q));
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < orderedIds.size(); i++) {
            InterviewQuestion q = byId.get(orderedIds.get(i));
            if (q.getOrderIndex() != i + 1) {
                q.setOrderIndex(i + 1);
                q.setUpdatedAt(now);
            }
        }
        questionRepo.saveAll(questions);
        return listForAdmin(typeId);
    }

    // ══ Answering ═════════════════════════════════════════════════════════════

    /** What an interviewer answers for this type today — active questions, in order. */
    @Transactional(readOnly = true)
    public List<InterviewQuestionDto> activeQuestions(Long typeId) {
        return questionRepo.findByInterviewTypeIdAndIsActiveTrueOrderByOrderIndexAscIdAsc(typeId)
                .stream().map(q -> toDto(q, 0L)).toList();
    }

    /**
     * Checks a PV's answers against the type's active questions and turns them into rows
     * (without report id yet). Called BEFORE the PV is written, so a bad answer leaves
     * nothing half-saved.
     *
     * <ul>
     *   <li>every answer must target an active question of this type, once;</li>
     *   <li>every required question must be answered;</li>
     *   <li>the value must fit the question: text length, slider bounds and step, options
     *       taken from the question's list.</li>
     * </ul>
     * An optional question left empty is simply not stored.
     */
    @Transactional(readOnly = true)
    public List<CandidateInterviewReportAnswer> prepareAnswers(Long typeId, List<InterviewAnswerRequest> answers) {
        List<InterviewQuestion> questions =
                questionRepo.findByInterviewTypeIdAndIsActiveTrueOrderByOrderIndexAscIdAsc(typeId);
        Map<Long, InterviewAnswerRequest> byQuestion = new LinkedHashMap<>();
        for (InterviewAnswerRequest a : answers == null ? List.<InterviewAnswerRequest>of() : answers) {
            if (a == null || a.questionId() == null) continue;
            if (byQuestion.put(a.questionId(), a) != null) {
                throw new BusinessRuleException("Une même question a reçu deux réponses.");
            }
        }
        Set<Long> known = questions.stream().map(InterviewQuestion::getId).collect(Collectors.toSet());
        for (Long id : byQuestion.keySet()) {
            if (!known.contains(id)) {
                throw new BusinessRuleException(
                        "Le questionnaire de cet entretien a changé entre-temps : rouvrez le PV pour le recharger.");
            }
        }

        List<CandidateInterviewReportAnswer> rows = new ArrayList<>();
        for (InterviewQuestion q : questions) {
            CandidateInterviewReportAnswer row = toAnswerRow(q, byQuestion.get(q.getId()));
            if (row == null) {
                if (Boolean.TRUE.equals(q.getIsRequired())) {
                    throw new BusinessRuleException("Répondez à la question obligatoire « " + q.getLabel() + " ».");
                }
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /** Replaces a PV's answers wholesale — a rewritten PV is answered again from scratch. */
    public void replaceAnswers(Long reportId, List<CandidateInterviewReportAnswer> rows) {
        if (reportId == null) return;
        answerRepo.deleteByReportId(reportId);
        if (rows == null || rows.isEmpty()) return;
        rows.forEach(r -> r.setReportId(reportId));
        answerRepo.saveAll(rows);
    }

    /** reportId → its answers in question order, for a batch of PVs (one query). */
    @Transactional(readOnly = true)
    public Map<Long, List<InterviewAnswerDto>> answersByReport(Collection<Long> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) return Map.of();
        Map<Long, List<InterviewAnswerDto>> byReport = new LinkedHashMap<>();
        for (CandidateInterviewReportAnswer a : answerRepo.findByReportIdInOrderByQuestionOrderAscIdAsc(reportIds)) {
            byReport.computeIfAbsent(a.getReportId(), k -> new ArrayList<>()).add(new InterviewAnswerDto(
                    a.getQuestionId(), a.getQuestionLabel(), a.getQuestionLabelEn(), a.getResponseType().name(),
                    a.getRatingMin(), a.getRatingMax(), a.getQuestionOrder(),
                    a.getValueText(), a.getValueNumber(), readList(a.getValueOptionsJson())));
        }
        return byReport;
    }

    // ══ helpers ═══════════════════════════════════════════════════════════════

    /** null = no answer given (blank text, no number, no option). Throws on an invalid value. */
    private CandidateInterviewReportAnswer toAnswerRow(InterviewQuestion q, InterviewAnswerRequest a) {
        if (a == null) return null;
        CandidateInterviewReportAnswer.CandidateInterviewReportAnswerBuilder row = CandidateInterviewReportAnswer.builder()
                .questionId(q.getId())
                .questionLabel(q.getLabel())
                .questionLabelEn(q.getLabelEn())
                .responseType(q.getResponseType())
                .questionOrder(q.getOrderIndex());
        String label = "« " + q.getLabel() + " »";
        switch (q.getResponseType()) {
            case TEXT -> {
                String text = a.text() == null ? "" : a.text().trim();
                if (text.isEmpty()) return null;
                if (text.length() > MAX_TEXT) {
                    throw new BusinessRuleException("La réponse à " + label + " dépasse " + MAX_TEXT + " caractères.");
                }
                row.valueText(text);
            }
            case RATING -> {
                BigDecimal n = a.number();
                if (n == null) return null;
                int min = q.getRatingMin(), max = q.getRatingMax(), step = q.getRatingStep();
                if (n.compareTo(BigDecimal.valueOf(min)) < 0 || n.compareTo(BigDecimal.valueOf(max)) > 0) {
                    throw new BusinessRuleException("La note de " + label + " doit être entre " + min + " et " + max + ".");
                }
                BigDecimal offset = n.subtract(BigDecimal.valueOf(min));
                if (offset.remainder(BigDecimal.valueOf(step)).compareTo(BigDecimal.ZERO) != 0) {
                    throw new BusinessRuleException("La note de " + label + " doit avancer par pas de " + step + ".");
                }
                row.valueNumber(n).ratingMin(min).ratingMax(max);
            }
            case SELECT, MULTI_SELECT -> {
                List<String> allowed = readList(q.getOptionsJson());
                List<String> picked = a.options() == null ? List.of() : a.options().stream()
                        .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
                if (picked.isEmpty()) return null;
                if (q.getResponseType() == InterviewQuestionType.SELECT && picked.size() > 1) {
                    throw new BusinessRuleException("Une seule réponse est attendue pour " + label + ".");
                }
                for (String p : picked) {
                    if (!allowed.contains(p)) {
                        throw new BusinessRuleException("« " + p + " » ne fait pas partie des choix de " + label
                                + " : rouvrez le PV pour recharger le questionnaire.");
                    }
                }
                // Stored in the question's own order, whatever order they were ticked in.
                List<String> ordered = allowed.stream().filter(picked::contains).toList();
                row.valueOptionsJson(writeList(ordered));
            }
        }
        return row.build();
    }

    private record Scale(int min, int max, int step) {}

    /** RATING scale from the request, 1 → 5 step 1 by default, checked. */
    private static Scale scaleOf(SaveInterviewQuestionRequest req) {
        int min  = req.ratingMin()  != null ? req.ratingMin()  : 1;
        int max  = req.ratingMax()  != null ? req.ratingMax()  : 5;
        int step = req.ratingStep() != null ? req.ratingStep() : 1;
        if (min < RATING_FLOOR || max > RATING_CEIL) {
            throw new BusinessRuleException("L'échelle doit rester entre " + RATING_FLOOR + " et " + RATING_CEIL + ".");
        }
        if (min >= max) throw new BusinessRuleException("Le minimum de l'échelle doit être inférieur au maximum.");
        if (step <= 0)  throw new BusinessRuleException("Le pas de l'échelle doit être positif.");
        if ((max - min) % step != 0) {
            throw new BusinessRuleException("Le pas (" + step + ") doit diviser l'écart entre minimum et maximum ("
                    + (max - min) + ").");
        }
        return new Scale(min, max, step);
    }

    /** Copies the request onto the entity; clears what does not apply to the response type. */
    private void apply(InterviewQuestion q, SaveInterviewQuestionRequest req) {
        InterviewQuestionType type = InterviewQuestionType.valueOf(req.responseType());
        q.setLabel(req.label().trim());
        q.setLabelEn(blankToNull(req.labelEn()));
        q.setHelpText(blankToNull(req.helpText()));
        q.setResponseType(type);
        q.setIsRequired(req.required() == null || req.required());

        q.setOptionsJson(null);
        q.setRatingMin(null); q.setRatingMax(null); q.setRatingStep(null);
        q.setRatingMinLabel(null); q.setRatingMaxLabel(null);

        switch (type) {
            case TEXT -> { /* nothing else */ }
            case RATING -> {
                Scale s = scaleOf(req);
                q.setRatingMin(s.min()); q.setRatingMax(s.max()); q.setRatingStep(s.step());
                q.setRatingMinLabel(blankToNull(req.ratingMinLabel()));
                q.setRatingMaxLabel(blankToNull(req.ratingMaxLabel()));
            }
            case SELECT, MULTI_SELECT -> {
                List<String> options = req.options() == null ? List.of() : req.options().stream()
                        .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
                if (options.size() < 2) {
                    throw new BusinessRuleException("Une question à choix doit proposer au moins deux options.");
                }
                q.setOptionsJson(writeList(options));
            }
        }
    }

    private InterviewType loadTypeInScope(Long typeId) {
        InterviewType type = typeRepo.findById(typeId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewType", typeId));
        assertPaysInScope(type.getPaysId());
        return type;
    }

    private InterviewQuestion loadQuestionInScope(Long questionId) {
        InterviewQuestion q = questionRepo.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewQuestion", questionId));
        loadTypeInScope(q.getInterviewTypeId());
        return q;
    }

    /** Same rule as InterviewTypeService.list: a pays outside the caller's scope is refused. */
    private void assertPaysInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.unfiltered()) return;
        if (paysId == null || !scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette entité n'est pas dans votre périmètre.");
        }
    }

    private Map<Long, Long> answerCounts(Collection<Long> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : answerRepo.countByQuestionIds(questionIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private InterviewQuestionDto toDto(InterviewQuestion q, long answerCount) {
        return new InterviewQuestionDto(
                q.getId(), q.getInterviewTypeId(), q.getLabel(), q.getLabelEn(), q.getHelpText(),
                q.getResponseType().name(), readList(q.getOptionsJson()),
                q.getRatingMin(), q.getRatingMax(), q.getRatingStep(),
                q.getRatingMinLabel(), q.getRatingMaxLabel(),
                Boolean.TRUE.equals(q.getIsRequired()), q.getOrderIndex(),
                Boolean.TRUE.equals(q.getIsActive()), answerCount);
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Unreadable options JSON {}: {}", json, e.getMessage());
            return List.of();
        }
    }

    private static String writeList(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise options", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
