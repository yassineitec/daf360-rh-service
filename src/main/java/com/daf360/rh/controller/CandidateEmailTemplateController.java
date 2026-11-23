package com.daf360.rh.controller;

import com.daf360.rh.dto.email.CandidateEmailDtos.DefaultTemplateDto;
import com.daf360.rh.dto.email.CandidateEmailDtos.SaveTemplateRequest;
import com.daf360.rh.dto.email.CandidateEmailDtos.TemplateDto;
import com.daf360.rh.service.CandidateEmailTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** « Administration > Modèles d'emails candidats » (V134). Permission checked in the service. */
@RestController
@RequestMapping("/api/hr/candidate-email-templates")
@RequiredArgsConstructor
public class CandidateEmailTemplateController {

    private final CandidateEmailTemplateService service;

    @GetMapping
    public List<TemplateDto> list(@RequestParam Long paysId) {
        return service.list(paysId);
    }

    @GetMapping("/defaults")
    public List<DefaultTemplateDto> defaults() {
        return service.defaults();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDto create(@Valid @RequestBody SaveTemplateRequest req, Authentication auth) {
        return service.create(req, actorId(auth));
    }

    @PutMapping("/{id}")
    public TemplateDto update(@PathVariable Long id, @Valid @RequestBody SaveTemplateRequest req,
                              Authentication auth) {
        return service.update(id, req, actorId(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
