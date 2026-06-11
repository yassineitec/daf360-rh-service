package com.daf360.rh.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "notification_routing_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_type_id", nullable = false)
    private NotificationEventType eventType;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "send_inapp", columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean sendInapp = true;

    @Column(name = "send_email", columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean sendEmail = false;

    @Column(name = "inapp_title_template", columnDefinition = "nvarchar(255)", nullable = false)
    private String inappTitleTemplate;

    @Column(name = "inapp_body_template", columnDefinition = "nvarchar(1000)", nullable = false)
    private String inappBodyTemplate;

    @Column(name = "email_subject_template", columnDefinition = "nvarchar(255)")
    private String emailSubjectTemplate;

    @Column(name = "email_body_template", columnDefinition = "nvarchar(max)")
    private String emailBodyTemplate;

    @Column(name = "is_active", columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", columnDefinition = "DATETIMEOFFSET")
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET")
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "rule", fetch = FetchType.LAZY)
    @Builder.Default
    private List<NotificationRoutingRecipient> inappRecipients = new java.util.ArrayList<>();

    @OneToMany(mappedBy = "rule", fetch = FetchType.LAZY)
    @Builder.Default
    private List<EmailRoutingRecipient> emailRecipients = new java.util.ArrayList<>();

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
