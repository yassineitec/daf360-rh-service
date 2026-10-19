package com.daf360.rh.dto.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A CIVP → CDI conversion request as the screens read it. The {@code can*} flags are what
 * THE CALLER may do now (permission, status and four-eyes already applied), so the front
 * shows exactly the buttons the backend will accept.
 */
public record CdiConversionDto(
        Long id,
        Long contractId,
        Long employeeProfileId,
        String employeeName,
        Long paysId,
        String status,
        Long requestedBy,
        String requestedByName,
        OffsetDateTime requestedAt,
        LocalDate requestedStartDate,
        BigDecimal proposedNetSalary,
        String justification,
        BigDecimal validatedNetSalary,
        LocalDate validatedStartDate,
        Long decidedBy,
        String decidedByName,
        OffsetDateTime decidedAt,
        Long convertedContractId,
        OffsetDateTime convertedAt,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        boolean canDecide,
        boolean canEscalate,
        boolean canCancel,
        boolean canConvert,
        List<Step> steps) {

    public record Step(
            Long id,
            String action,
            Long actorUserId,
            String actorName,
            String comment,
            BigDecimal netSalary,
            LocalDate startDate,
            OffsetDateTime createdAt) {}
}
