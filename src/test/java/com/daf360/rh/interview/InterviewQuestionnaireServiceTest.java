package com.daf360.rh.interview;

import com.daf360.rh.domain.CandidateInterviewReportAnswer;
import com.daf360.rh.domain.InterviewQuestion;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewQuestionType;
import com.daf360.rh.dto.interview.InterviewAnswerRequest;
import com.daf360.rh.dto.interview.SaveInterviewQuestionRequest;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.CandidateInterviewReportAnswerRepository;
import com.daf360.rh.repository.InterviewQuestionRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.InterviewQuestionnaireService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterviewQuestionnaireServiceTest {

    @Mock InterviewQuestionRepository              questionRepo;
    @Mock CandidateInterviewReportAnswerRepository answerRepo;
    @Mock InterviewTypeRepository                  typeRepo;
    @Mock TenantService                            tenantService;

    @InjectMocks InterviewQuestionnaireService service;

    private static final Long TYPE_ID = 7L;

    private static InterviewQuestion rating(long id, boolean required) {
        return InterviewQuestion.builder().id(id).interviewTypeId(TYPE_ID).label("Communication")
                .responseType(InterviewQuestionType.RATING).ratingMin(1).ratingMax(5).ratingStep(1)
                .isRequired(required).orderIndex(1).isActive(true).build();
    }

    private static InterviewQuestion select(long id, InterviewQuestionType type) {
        return InterviewQuestion.builder().id(id).interviewTypeId(TYPE_ID).label("Langues")
                .responseType(type).optionsJson("[\"Français\",\"Anglais\",\"Arabe\"]")
                .isRequired(true).orderIndex(2).isActive(true).build();
    }

    private void questions(InterviewQuestion... qs) {
        when(questionRepo.findByInterviewTypeIdAndIsActiveTrueOrderByOrderIndexAscIdAsc(TYPE_ID))
                .thenReturn(List.of(qs));
    }

    // ── answers ──────────────────────────────────────────────────────────────

    @Test
    void prepareAnswers_missingRequired_isRejected() {
        questions(rating(1L, true));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID, List.of()));
    }

    @Test
    void prepareAnswers_optionalLeftEmpty_isSkipped() {
        questions(rating(1L, false));
        assertTrue(service.prepareAnswers(TYPE_ID, List.of(new InterviewAnswerRequest(1L, null, null, null))).isEmpty());
    }

    @Test
    void prepareAnswers_ratingOutOfScaleOrOffStep_isRejected() {
        questions(rating(1L, true));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(1L, null, BigDecimal.valueOf(6), null))));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(1L, null, new BigDecimal("2.5"), null))));
    }

    @Test
    void prepareAnswers_validRating_snapshotsTheQuestion() {
        questions(rating(1L, true));
        List<CandidateInterviewReportAnswer> rows = service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(1L, null, BigDecimal.valueOf(4), null)));
        assertEquals(1, rows.size());
        assertEquals("Communication", rows.get(0).getQuestionLabel());
        assertEquals(5, rows.get(0).getRatingMax());
        assertEquals(0, BigDecimal.valueOf(4).compareTo(rows.get(0).getValueNumber()));
    }

    @Test
    void prepareAnswers_selectWithTwoValues_isRejected() {
        questions(select(2L, InterviewQuestionType.SELECT));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(2L, null, null, List.of("Français", "Anglais")))));
    }

    @Test
    void prepareAnswers_unknownOption_isRejected() {
        questions(select(2L, InterviewQuestionType.MULTI_SELECT));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(2L, null, null, List.of("Allemand")))));
    }

    @Test
    void prepareAnswers_multiSelect_isStoredInQuestionOrder() {
        questions(select(2L, InterviewQuestionType.MULTI_SELECT));
        List<CandidateInterviewReportAnswer> rows = service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(2L, null, null, List.of("Arabe", "Français"))));
        assertEquals("[\"Français\",\"Arabe\"]", rows.get(0).getValueOptionsJson());
    }

    @Test
    void prepareAnswers_questionNotInQuestionnaire_isRejected() {
        questions(rating(1L, false));
        assertThrows(BusinessRuleException.class, () -> service.prepareAnswers(TYPE_ID,
                List.of(new InterviewAnswerRequest(99L, "x", null, null))));
    }

    // ── configuration ────────────────────────────────────────────────────────

    @Test
    void update_answeredRating_cannotChangeItsScale() {
        InterviewQuestion q = rating(1L, true);
        when(questionRepo.findById(1L)).thenReturn(Optional.of(q));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(InterviewType.builder().id(TYPE_ID).paysId(1L).build()));
        when(tenantService.getPaysScope()).thenReturn(new PaysScopeContext.Scope(false, Set.of(1L)));
        when(answerRepo.countByQuestionId(1L)).thenReturn(3L);

        var req = new SaveInterviewQuestionRequest("Communication", null, null, "RATING",
                null, 1, 10, 1, null, null, true);
        assertThrows(BusinessRuleException.class, () -> service.update(1L, req));
        verify(questionRepo, never()).save(any());
    }

    @Test
    void create_selectWithOneOption_isRejected() {
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(InterviewType.builder().id(TYPE_ID).paysId(1L).build()));
        when(tenantService.getPaysScope()).thenReturn(new PaysScopeContext.Scope(true, Set.of()));

        var req = new SaveInterviewQuestionRequest("Disponibilité", null, null, "SELECT",
                List.of("Immédiate"), null, null, null, null, null, true);
        assertThrows(BusinessRuleException.class, () -> service.create(TYPE_ID, req));
        verify(questionRepo, never()).save(any());
    }
}
