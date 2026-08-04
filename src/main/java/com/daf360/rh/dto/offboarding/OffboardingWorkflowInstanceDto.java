package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class OffboardingWorkflowInstanceDto {

    private Long id;
    private Long paysId;
    private Long employeeProfileId;
    private Long contractId;
    private LocalDate triggerDate;
    private LocalDate lastWorkingDay;
    private String departureReason;
    private String departureNotes;
    private String status;
    private Long initiatedBy;
    private Long validatedBy;
    private OffsetDateTime validatedAt;
    private Long cancelledBy;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;
    private Boolean slaBreachFlag;
    private OffsetDateTime completionDate;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<OffboardingTaskDto> tasks;

    private String employeeFullName;
    /** Canonical GENDER value_code — picks the male/female avatar. Nullable. */
    private String employeeGender;
    /** employee_profiles.photo_url when a photo is on file; null otherwise. */
    private String employeePhotoUrl;
    private Long   handoverManagerProfileId;
    private String handoverManagerName;
    /**
     * The manager's PORTAL user id, not their profile id.
     *
     * Stage 2's manager panel may only be stamped by that person, and the client has the
     * signed-in *user* id — it cannot compare against a profile id. Null when no handover
     * manager is set or the profile has no portal account, which is precisely when RH has
     * to stand in.
     */
    private Long   handoverManagerUserId;

    // ── Stage 1 — Déclaration (V57) ──────────────────────────────────────────
    // camelCase names match `OffboardingPendingFields` in the frontend model, which
    // already declares and binds them.
    private String    justificationDocumentUrl;
    private String    justificationDocumentName;
    private String    noticePeriodLabel;
    private Boolean   noticeWaiverRequested;
    private LocalDate theoreticalExitDate;

    // ── Stage 2 — Validation Manager & RH (V59) ──────────────────────────────
    private Long           managerValidatedBy;
    /** Resolved display name, so the panel does not render a bare id. */
    private String         managerValidatedByName;
    private OffsetDateTime managerValidatedAt;
    private String         managerComment;
    private Long           hrValidatedBy;
    private String         hrValidatedByName;
    private OffsetDateTime hrValidatedAt;
    private Boolean        noticePaidNotWorked;

    // ── Stage 3 — Passation (V60) ────────────────────────────────────────────
    private String handoverMinutesUrl;
    private String handoverMinutesName;

    // ── Stage 4 — Informatique & Matériel (V61) ──────────────────────────────
    private OffsetDateTime accountDeactivationAt;
    private String         dischargeDocumentUrl;
    private String         dischargeDocumentName;

    // ── Stage 6 — Solde de tout compte (V63) ─────────────────────────────────
    private LocalDate                settlementExecutionDate;
    /** Null while no line exists — stage 6 then renders its own "add the first line" state. */
    private OffboardingSettlementDto settlement;
    /** Joined and masked from the profile's bank details; stage 6 stores no bank data. */
    private String                   settlementPaymentMode;

    /**
     * All three checklist groups in one list — HANDOVER, ACCESS and KIT. The client splits
     * them with `checklistOf(items, group)`; three arrays on the wire would just be three
     * things to keep in sync.
     */
    private List<OffboardingChecklistItemDto> checklistItems;
}
