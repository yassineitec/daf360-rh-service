package com.daf360.rh.controller;

import com.daf360.rh.dto.help.HelpFaqDto;
import com.daf360.rh.dto.help.SaveHelpFaqRequest;
import com.daf360.rh.service.HelpFaqService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin › Assistant & FAQ, and the published questions read by the assistant / help page. */
@RestController
@RequestMapping("/api/hr/help-faq")
@RequiredArgsConstructor
public class HelpFaqController {

    private final HelpFaqService service;

    /** Every question (admin card). */
    @GetMapping
    public List<HelpFaqDto> list() {
        return service.listAll();
    }

    /** Published questions (assistant, help page) — any signed-in user. */
    @GetMapping("/active")
    public List<HelpFaqDto> listActive() {
        return service.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HelpFaqDto create(@Valid @RequestBody SaveHelpFaqRequest req, Authentication auth) {
        return service.create(req, actorId(auth));
    }

    @PutMapping("/{id}")
    public HelpFaqDto update(@PathVariable Long id, @Valid @RequestBody SaveHelpFaqRequest req,
                             Authentication auth) {
        return service.update(id, req, actorId(auth));
    }

    @PatchMapping("/{id}/activate")
    public HelpFaqDto activate(@PathVariable Long id, Authentication auth) {
        return service.setActive(id, true, actorId(auth));
    }

    @PatchMapping("/{id}/deactivate")
    public HelpFaqDto deactivate(@PathVariable Long id, Authentication auth) {
        return service.setActive(id, false, actorId(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
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
