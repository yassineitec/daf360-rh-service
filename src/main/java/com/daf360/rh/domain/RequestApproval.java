package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [request_approvals] in DAF360_HR.
 * Schema verified 2026-05-31: 7 cols.
 * approver_id is NOT NULL (FK → Users.id).
 * decision: APPROVED | REJECTED
 * level: L1 | L2
 */
@Entity
@Table(name = "request_approvals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_request_id", nullable = false)
    private Long employeeRequestId;

    @Column(name = "level", nullable = false, length = 10)
    private String level;

    @Column(name = "approver_id", nullable = false)
    private Long approverId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "comment", length = 500, columnDefinition = "nvarchar(500)")
    private String comment;

    @Column(name = "decision_date", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime decisionDate;
}
