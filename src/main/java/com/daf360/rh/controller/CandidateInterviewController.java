package com.daf360.rh.controller;

import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.MyInterviewEventDto;
import com.daf360.rh.dto.interview.UpdateInterviewRequest;
import com.daf360.rh.dto.interview.UserPickerDto;
import com.daf360.rh.service.CandidateInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CandidateInterviewController {

    private final CandidateInterviewService service;

    @GetMapping("/api/hr/interviews/users")
    public List<UserPickerDto> interviewUsers(@RequestParam Long paysId) {
        return service.listInterviewUsers(paysId);
    }

    @GetMapping("/api/hr/candidates/{candidateId}/interviews")
    public List<CandidateInterviewDto> list(@PathVariable Long candidateId) {
        return service.listByCandidate(candidateId);
    }

    /**
     * Interviews assigned to the current user (as interviewer) in a date range,
     * as calendar events — feeds the shell home calendar. from/to = ISO dates.
     */
    @GetMapping("/api/hr/interviews/my")
    public List<MyInterviewEventDto> myInterviews(@RequestParam String from,
                                                  @RequestParam String to,
                                                  Authentication auth) {
        return service.listMyInterviews(actorId(auth), from, to);
    }

    @PostMapping("/api/hr/candidates/{candidateId}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateInterviewDto create(@PathVariable Long candidateId,
                                        @Valid @RequestBody CreateInterviewRequest req,
                                        Authentication auth) {
        return service.create(candidateId, req, actorId(auth));
    }

    @PutMapping("/api/hr/candidates/{candidateId}/interviews/{interviewId}")
    public CandidateInterviewDto update(@PathVariable Long candidateId,
                                        @PathVariable Long interviewId,
                                        @Valid @RequestBody UpdateInterviewRequest req,
                                        Authentication auth) {
        return service.update(interviewId, req, actorId(auth));
    }

    /** JWT sub = String.valueOf(userId). Null-safe: request may arrive without a valid token. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
