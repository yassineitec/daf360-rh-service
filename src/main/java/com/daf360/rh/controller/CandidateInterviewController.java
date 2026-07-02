package com.daf360.rh.controller;

import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
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

    private Long actorId(Authentication auth) {
        return Long.valueOf(auth.getName());
    }
}
