package com.daf360.rh.controller;

import com.daf360.rh.dto.interview.CreateInterviewTypeRequest;
import com.daf360.rh.dto.interview.InterviewTypeDto;
import com.daf360.rh.dto.interview.UpdateInterviewTypeRequest;
import com.daf360.rh.service.InterviewTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/interview-types")
@RequiredArgsConstructor
public class InterviewTypeController {

    private final InterviewTypeService service;

    @GetMapping
    public List<InterviewTypeDto> list() {
        return service.list();
    }

    @GetMapping("/active")
    public List<InterviewTypeDto> listActive(@RequestParam Long paysId) {
        return service.listActive(paysId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewTypeDto create(@Valid @RequestBody CreateInterviewTypeRequest req,
                                   Authentication auth) {
        return service.create(req, actorId(auth));
    }

    @PutMapping("/{id}")
    public InterviewTypeDto update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateInterviewTypeRequest req,
                                   Authentication auth) {
        return service.update(id, req, actorId(auth));
    }

    @PatchMapping("/{id}/deactivate")
    public InterviewTypeDto deactivate(@PathVariable Long id, Authentication auth) {
        return service.deactivate(id, actorId(auth));
    }

    @PatchMapping("/{id}/activate")
    public InterviewTypeDto activate(@PathVariable Long id, Authentication auth) {
        return service.activate(id, actorId(auth));
    }

    private Long actorId(Authentication auth) {
        return Long.valueOf(auth.getName());
    }
}
