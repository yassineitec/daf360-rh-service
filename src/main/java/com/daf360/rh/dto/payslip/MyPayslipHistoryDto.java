package com.daf360.rh.dto.payslip;

import com.daf360.rh.service.sharepoint.SharePointStatus;

import java.util.List;

/**
 * An employee's payslip history, and — when it is empty — why.
 *
 * <p>The status is part of the payload rather than an HTTP code on purpose. Every non-FOUND
 * outcome here is a NORMAL state, not an error: Egypt has no payroll tree configured
 * (NO_CONFIG), the Tunisian folders are created as people are first paid (FOLDER_MISSING),
 * and a deployment without Graph credentials answers UNAVAILABLE. Returning 404 or 500 for
 * those would make the page look broken and would bury the one thing the employee needs to
 * be told, which is that there is nothing to download YET rather than that something failed.
 *
 * @param status  FOUND when the folder resolved, whatever the file count
 * @param items   newest period first; empty whenever status != FOUND
 */
public record MyPayslipHistoryDto(SharePointStatus status, List<MyPayslipDto> items) {

    public static MyPayslipHistoryDto empty(SharePointStatus status) {
        return new MyPayslipHistoryDto(status, List.of());
    }
}
