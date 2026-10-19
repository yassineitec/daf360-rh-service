package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.CdiConversionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[cdi_conversion_requests] (V119): a request to turn an active CIVP contract
 * into a CDI, validated on the technical and salary side before RH carries it out.
 *
 * Who may act is a matter of PERMISSIONS, never of names — RH_REQUEST_CDI_CONVERSION files,
 * RH_VALIDATE_CDI_CONVERSION decides or escalates, RH_ARBITRATE_CDI_CONVERSION decides an
 * escalated request, RH_MANAGE_LIFECYCLE is told of the outcome and converts. Every action
 * is also written to {@link CdiConversionStep} (the timeline); this row keeps the current
 * state and the values finally retained.
 *
 * FKs are raw Longs like the rest of rh-service's flat request tables.
 */
@Entity
@Table(name = "cdi_conversion_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdiConversionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The CIVP contract to convert. */
    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CdiConversionStatus status;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_start_date", nullable = false)
    private LocalDate requestedStartDate;

    /** Net monthly salary proposed for the CDI. */
    @Column(name = "proposed_net_salary", nullable = false, precision = 14, scale = 3)
    private BigDecimal proposedNetSalary;

    @Column(name = "justification", nullable = false, length = 2000, columnDefinition = "nvarchar(2000)")
    private String justification;

    /** The salary retained by the decision (the validator / arbiter may change the proposal). */
    @Column(name = "validated_net_salary", precision = 14, scale = 3)
    private BigDecimal validatedNetSalary;

    /** The CDI start date retained by the decision. */
    @Column(name = "validated_start_date")
    private LocalDate validatedStartDate;

    /** Who took the final decision (APPROVED / REJECTED) and when. */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** The validator who escalated — kept out of the arbitration (four-eyes). */
    @Column(name = "escalated_by")
    private Long escalatedBy;

    /** The CDI contract created when RH carried out an APPROVED request. */
    @Column(name = "converted_contract_id")
    private Long convertedContractId;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Approved but not converted yet: blocks a new request, offers the conversion to RH. */
    public boolean isAwaitingConversion() {
        return status == CdiConversionStatus.APPROVED && convertedContractId == null;
    }
}
