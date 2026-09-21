package com.daf360.rh.controller;

import com.daf360.rh.dto.payslip.PayslipBatchResultDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.service.payslip.PayslipBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Monthly payslip batch: one multi-page PDF (one employee per page) in, one split-and-filed
 * report out. See {@link PayslipBatchService} for the matching/upload logic.
 */
@RestController
@RequestMapping("/api/hr/payslips")
@RequiredArgsConstructor
public class PayslipBatchController {

    private final PayslipBatchService payslipBatchService;

    @PostMapping(value = "/process-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('RH_MANAGE_PAYSLIPS')")
    public PayslipBatchResultDto processBatch(
            @RequestParam("file") MultipartFile file,
            @RequestParam("paysId") Long paysId,
            @RequestParam("periodYear") int periodYear,
            @RequestParam("periodMonth") int periodMonth) throws IOException {

        if (file.isEmpty()) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED, "Le fichier PDF est vide.");
        }
        if (!MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED,
                    "Le fichier doit etre un PDF (recu : " + file.getContentType() + ").");
        }
        if (periodMonth < 1 || periodMonth > 12) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Le mois doit etre entre 1 et 12.");
        }

        return payslipBatchService.processBatch(file.getBytes(), paysId, periodYear, periodMonth);
    }
}
