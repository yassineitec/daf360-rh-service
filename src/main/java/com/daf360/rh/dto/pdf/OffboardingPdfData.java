package com.daf360.rh.dto.pdf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inputs for the three offboarding documents rendered by pdf-service.
 *
 * Deliberately plain: {@code PdfDocumentService} must not depend on the offboarding entities
 * (it is shared by the request workflow), and the offboarding service must not know the
 * Handlebars placeholder names. These records are the contract between the two.
 */
public final class OffboardingPdfData {

    private OffboardingPdfData() { }

    /** One line of the décharge de restitution — an asset and whether it actually came back. */
    public record AssetLine(
            String label,
            String serialNumber,
            LocalDate returnedDate,
            String condition,
            String notes,
            boolean writtenOff) { }

    /** Everything the décharge de restitution needs beyond the employee's own identity. */
    public record DischargeData(
            LocalDate lastWorkingDay,
            String itAgentName,
            List<AssetLine> assets) { }

    /** Everything the attestation de fin de contrat needs. */
    public record EndOfContractData(
            LocalDate endDate,
            String departureReason,
            String noticePeriodLabel,
            Boolean noticeWorked) { }

    /** One settlement line. {@code amount} is signed — a negative value is a retenue. */
    public record SettlementLine(
            String label,
            BigDecimal amount,
            String note) { }

    /** Everything the reçu pour solde de tout compte needs. */
    public record SettlementReceiptData(
            LocalDate lastWorkingDay,
            String departureReason,
            String paymentMode,
            LocalDate executionDate,
            List<SettlementLine> lines,
            BigDecimal total) { }
}
