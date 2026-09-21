package com.daf360.rh.dto.payslip;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Report returned after processing one monthly payslip batch PDF. */
@Data
@Builder
public class PayslipBatchResultDto {

    private int totalPages;
    private int successCount;
    private int errorCount;
    private int unidentifiedCount;
    private int duplicateCount;

    /** One entry per page, in page order — what the UI renders as the processing report. */
    private List<PayslipPageResultDto> details;
}
