package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[cdi_conversion_request_steps] (V119): one row per action on a CIVP → CDI
 * request — SUBMIT, APPROVE, REJECT, ESCALATE, CANCEL, CONVERT — with the actor, the
 * comment, and the salary / date that action stated. Insert-only; read as the timeline.
 */
@Entity
@Table(name = "cdi_conversion_request_steps")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdiConversionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "comment", length = 2000, columnDefinition = "nvarchar(2000)")
    private String comment;

    @Column(name = "net_salary", precision = 14, scale = 3)
    private BigDecimal netSalary;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
