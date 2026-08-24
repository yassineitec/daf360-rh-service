package com.daf360.rh.dto.mission;

import com.daf360.rh.domain.enums.MissionScope;
import com.daf360.rh.domain.enums.MissionStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A mission as every screen reads it — the manager's list, the RH billeterie queue, the
 * finance queue and the employee's self-service card all take this one shape.
 *
 * The display names are resolved and inlined (employeeName, responsableDisplayName…): the
 * finance frontend calls the RH API directly and has no users_ref replica of its own to
 * join against, so the ids alone would render as numbers.
 */
@Data
public class MissionDto {

    private Long id;
    private Long paysId;

    private Long   employeeUserId;
    private String employeeName;
    private String employeeRoleName;

    private Long   createdBy;
    private String createdByName;

    private Long   responsableUserId;
    /** The colleague's full name, or the free-text external contact. */
    private String responsableDisplayName;

    private String    title;
    private String    details;
    private LocalDate startDate;
    private LocalDate endDate;
    /** Inclusive day count — computed, never stored. */
    private Integer   durationDays;

    private MissionScope scope;
    private Long         destinationPaysId;
    private String       countryLabel;
    private String       city;
    private String       address;

    private MissionStatus status;

    private Long           hrValidatedBy;
    private String         hrValidatedByName;
    private OffsetDateTime hrValidatedAt;
    private String         hrNotes;

    private Long           financeDecidedBy;
    private String         financeDecidedByName;
    private OffsetDateTime financeDecidedAt;
    private String         financeNotes;

    private OffsetDateTime cancelledAt;
    private String         cancellationReason;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** Null until RH opens the billeterie form. */
    private MissionExpenseDto expenses;

    /**
     * The employee's open ask, if any. Kept on the mission so the RH queue can show a
     * "modification demandée" flag without a second call per row.
     */
    private MissionChangeRequestDto pendingChangeRequest;

    /** Only populated on the detail endpoint — the queues do not need the trail. */
    private List<MissionHistoryEntryDto> history;
}
