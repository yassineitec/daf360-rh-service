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

    @Column(name = "role_id", nullable = false)
    private Long roleId;

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
