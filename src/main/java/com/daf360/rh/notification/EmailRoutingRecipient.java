package com.daf360.rh.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_routing_recipients")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailRoutingRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routing_rule_id", nullable = false)
    private NotificationRoutingRule rule;

    /**
     * Role this recipient targets. NULL when recipientMode is PERMISSION, which
     * targets a permission code instead — hence nullable since V92.
     */
    @Column(name = "role_id")
    private Long roleId;

    /** ALL (default) | MANAGER | PERMISSION — see NotificationRecipientMode. */
    @Column(name = "recipient_mode", length = 20)
    @Builder.Default
    private String recipientMode = NotificationRecipientMode.ALL.name();

    /** Permission targeted when recipientMode is PERMISSION; NULL otherwise. */
    @Column(name = "permission_code", length = 100)
    private String permissionCode;

    @Column(name = "recipient_field", length = 10, columnDefinition = "varchar(10) DEFAULT 'TO'")
    @Builder.Default
    private String recipientField = "TO";

    @Column(name = "is_active", columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
