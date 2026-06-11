package com.daf360.rh.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_event_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_code", length = 100, nullable = false)
    private String eventCode;

    @Column(name = "label_fr", columnDefinition = "nvarchar(255)")
    private String labelFr;

    @Column(name = "label_en", columnDefinition = "nvarchar(255)")
    private String labelEn;

    @Column(name = "description_fr", columnDefinition = "nvarchar(500)")
    private String descriptionFr;

    @Column(name = "module", length = 50)
    private String module;

    @Column(name = "supports_email", columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean supportsEmail = false;

    @Column(name = "is_system", columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isSystem = true;

    @Column(name = "is_active", columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
