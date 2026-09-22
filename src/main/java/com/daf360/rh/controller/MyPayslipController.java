package com.daf360.rh.controller;

import com.daf360.rh.dto.payslip.MyPayslipHistoryDto;
import com.daf360.rh.service.payslip.MyPayslipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The employee's own payslip history — the read side of {@link PayslipBatchController}.
 *
 * <p>Deliberately NOT {@code @PreAuthorize}'d, exactly like {@code MissionController}'s
 * {@code /my} endpoints: reading your own payslip is not a privilege, and a permission code
 * here would have to be granted to every employee to be correct, which is no check at all.
 * What protects the data is that neither endpoint accepts a profile id, a folder or a path —
 * everything is recomputed from the JWT inside {@link MyPayslipService}.
 */
@RestController
@RequestMapping("/api/hr/payslips/my")
@RequiredArgsConstructor
public class MyPayslipController {

    private final MyPayslipService service;

    /** Every payslip filed for the caller, newest first, plus why the list is empty if it is. */
    @GetMapping
    public MyPayslipHistoryDto history(Authentication auth) {
        return service.history(actorId(auth));
    }

    /**
     * One payslip's bytes.
     *
     * <p>{@code fileName} comes straight back from {@link #history} and is treated as a handle,
     * not a path — see {@link MyPayslipService#download}.
     */
    @GetMapping("/{year}/download")
    public ResponseEntity<byte[]> download(@PathVariable int year,
                                           @RequestParam String fileName,
                                           Authentication auth) {
        MyPayslipService.PayslipFile file = service.download(actorId(auth), year, fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                // Both forms: `filename` for older clients, `filename*` because the convention
                // puts accented employee names in the file name ("Kods_CHÉRIF_Mai_2026.PDF")
                // and a raw non-ASCII header value is what turns a download into mojibake.
                .header("Content-Disposition",
                        "attachment; filename=\"" + asciiFallback(file.fileName()) + "\"; "
                        + "filename*=UTF-8''" + URLEncoder.encode(file.fileName(),
                                StandardCharsets.UTF_8).replace("+", "%20"))
                .body(file.bytes());
    }

    /**
     * The caller's user id, from the JWT principal.
     *
     * <p>Same convention as {@code MissionController.actorId}, including its refusal to fall
     * back to a default id: here the id decides WHOSE payslips are listed, so a fallback would
     * not be a convenience, it would be a data leak.
     */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new AccessDeniedException("Utilisateur non authentifié");
        }
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException notAnId) {
            throw new AccessDeniedException("Principal illisible");
        }
    }

    /** Non-ASCII replaced so the quoted `filename` parameter stays a legal header value. */
    private String asciiFallback(String fileName) {
        return fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
    }
}
