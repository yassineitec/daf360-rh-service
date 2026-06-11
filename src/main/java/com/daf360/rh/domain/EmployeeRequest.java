package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.RequestStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [employee_requests] in DAF360_HR.
 * Schema verified 2026-05-31: 13 cols, all datetimeoffset for timestamps.
 *
 * FKs: employee_profile_id → employee_profiles, request_type_id → request_type_catalog,
 *      pays_id → pays, assigned_officer_id → Users
 */
@Entity
@Table(name = "employee_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "request_type_id", nullable = false)
    private Long requestTypeId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "submission_date", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime submissionDate;

    @Column(name = "submission_channel", nullable = false, length = 20)
    @Builder.Default
    private String submissionChannel = "WEB";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private RequestStatus status = RequestStatus.SUBMITTED;

    @Column(name = "assigned_officer_id")
    private Long assignedOfficerId;

    @Column(name = "resolution_date", columnDefinition = "datetimeoffset")
    private OffsetDateTime resolutionDate;

    @Column(name = "closure_comment", length = 500, columnDefinition = "nvarchar(500)")
    private String closureComment;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;
}
