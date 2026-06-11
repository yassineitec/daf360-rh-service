package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps the shared [audit_log] table in DAF360_HR.
 * Insert-only — never update or delete audit records.
 *
 * Column mapping (DB column → Java field):
 *   userId     → userId       (varchar 50 — the actor's string id or "SYSTEM")
 *   entityType → entityType   (varchar 100)
 *   entityId   → entityId     (varchar 100 — stored as string in DB)
 *   oldValue   → oldValue     (text)
 *   newValue   → newValue     (text)
 *   ipAddress  → ipAddress    (varchar 50)
 *   timestamp  → timestamp    (datetimeoffset)
 *   status     → status       (varchar 50 — optional outcome code)
 *   module     → module       (varchar 50 — logical module: HR, LEAVE…)
 *   pays_id    → paysId
 */
@Entity
@Table(name = "audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "userId", length = 50)
    private String userId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "entityType", length = 100)
    private String entityType;

    /** Stored as varchar(100) in DB — convert from Long when building. */
    @Column(name = "entityId", length = 100)
    private String entityId;

    @Column(name = "oldValue", columnDefinition = "text")
    private String oldValue;

    @Column(name = "newValue", columnDefinition = "text")
    private String newValue;

    @Column(name = "ipAddress", length = 50)
    private String ipAddress;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "module", length = 50)
    private String module;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "timestamp", columnDefinition = "datetimeoffset")
    private OffsetDateTime timestamp;
}
