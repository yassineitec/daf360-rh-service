package com.daf360.rh.controller;

import com.daf360.rh.dto.provisioning.*;
import com.daf360.rh.service.ItProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/it-provisioning")
@RequiredArgsConstructor
public class ItProvisioningController {

    private final ItProvisioningService provisioningService;

    @GetMapping("/pending")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public List<ProvisioningListItem> getPendingList() {
        return provisioningService.getPendingList();
    }

    @GetMapping("/all")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public List<ProvisioningListItem> getAllList() {
        return provisioningService.getAllList();
    }

    @GetMapping("/{id}")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public ProvisioningResponse getOne(@PathVariable Long id) {
        return provisioningService.getProvisioning(id);
    }

    @PatchMapping("/{id}")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public ProvisioningResponse update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateProvisioningRequest request,
                                       Authentication auth) {
        return provisioningService.updateProvisioning(id, request, actorId(auth));
    }

    @PostMapping("/{id}/submit-email")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public ProvisioningResponse submitEmail(@PathVariable Long id,
                                            @Valid @RequestBody SubmitEmailRequest request,
                                            Authentication auth) {
        return provisioningService.submitEmail(id, request.getMs365Email(), actorId(auth));
    }

    @PostMapping("/{id}/complete")
    //@PreAuthorize("hasPermission(null, 'IT_PROVISIONING')")
    public ProvisioningResponse complete(@PathVariable Long id, Authentication auth) {
        return provisioningService.completeProvisioning(id, actorId(auth));
    }

    /** JWT sub = String.valueOf(userId) — see portal JwtTokenService. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
