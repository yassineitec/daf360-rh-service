package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[employee_lifecycle_alerts] — scheduled alerts for lifecycle events.
 * D3-102: alert 30 days before CDD/CIVP/STAGE expiry.
 * D3-103: recipients stored as JSON array (e.g. ["RH","IT","DIRECTEUR_PAYS"]).
 *
 * CRON job queries the filtered index IX_ELA_AlertDate (WHERE is_sent = 0).
 */
@Entity
@Table(name = "employee_lifecycle_alerts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeLifecycleAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private EmployeeContract contract;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    @Column(name = "alert_date", nullable = false)
    private LocalDate alertDate;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** JSON array of role keys, e.g. ["RH","IT","DIRECTEUR_PAYS"] */
    @Column(name = "recipients", nullable = false, length = 500, columnDefinition = "nvarchar(500)")
    private String recipients;

    @Column(name = "is_sent", nullable = false)
    @Builder.Default
    private Boolean isSent = false;

    @Column(name = "sent_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime sentAt;

    @Column(name = "is_acknowledged", nullable = false)
    @Builder.Default
    private Boolean isAcknowledged = false;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime acknowledgedAt;
}
