package com.daf360.rh.controller;

import com.daf360.rh.dto.interview.InterviewQuestionDto;
import com.daf360.rh.dto.interview.ReorderInterviewQuestionsRequest;
import com.daf360.rh.dto.interview.SaveInterviewQuestionRequest;
import com.daf360.rh.service.InterviewQuestionnaireService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Questionnaire of each interview type (V135) — Administration › Types d'entretiens. */
@RestController
@RequestMapping("/api/hr/interview-types")
@RequiredArgsConstructor
public class InterviewQuestionController {

    private final InterviewQuestionnaireService service;

    /** typeId → number of active questions, for the types table. */
    @GetMapping("/question-counts")
    public Map<Long, Long> counts(@RequestParam Long paysId) {
        return service.activeCountsByType(paysId);
    }

    @GetMapping("/{typeId}/questions")
    public List<InterviewQuestionDto> list(@PathVariable Long typeId) {
        return service.listForAdmin(typeId);
    }

    @PostMapping("/{typeId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewQuestionDto create(@PathVariable Long typeId,
                                       @Valid @RequestBody SaveInterviewQuestionRequest req) {
        return service.create(typeId, req);
    }

    @PutMapping("/{typeId}/questions/order")
    public List<InterviewQuestionDto> reorder(@PathVariable Long typeId,
                                              @Valid @RequestBody ReorderInterviewQuestionsRequest req) {
        return service.reorder(typeId, req.questionIds());
    }

    @PutMapping("/questions/{questionId}")
    public InterviewQuestionDto update(@PathVariable Long questionId,
                                       @Valid @RequestBody SaveInterviewQuestionRequest req) {
        return service.update(questionId, req);
    }

    @PatchMapping("/questions/{questionId}/archive")
    public InterviewQuestionDto archive(@PathVariable Long questionId) {
        return service.setActive(questionId, false);
    }

    @PatchMapping("/questions/{questionId}/restore")
    public InterviewQuestionDto restore(@PathVariable Long questionId) {
        return service.setActive(questionId, true);
    }

    @DeleteMapping("/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long questionId) {
        service.delete(questionId);
    }
}
