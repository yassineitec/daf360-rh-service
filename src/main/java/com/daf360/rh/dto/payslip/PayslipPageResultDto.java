package com.daf360.rh.dto.payslip;

import lombok.Builder;
import lombok.Data;

/** Outcome for one page of a processed payslip batch PDF. */
@Data
@Builder
public class PayslipPageResultDto {

    /** 1-based position of this page in the source PDF. */
    private int pageNumber;

    /** Matricule read off the page text, or null when none was found. */
    private String matricule;

    /** The matched employee's Users.fullName, or null when matricule is null/unmatched. */
    private String employeeFullName;

    /** SUCCESS | ERROR | UNIDENTIFIED | DUPLICATE — see PayslipBatchService. */
    private String status;

    /** SharePoint URL of the uploaded single-page PDF, or null when not uploaded. */
    private String sharePointUrl;

    /** Set when status != SUCCESS. */
    private String errorMessage;
}
