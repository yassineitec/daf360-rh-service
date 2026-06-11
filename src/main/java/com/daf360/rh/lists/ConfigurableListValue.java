package com.daf360.rh.lists;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "configurable_list_values")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurableListValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_type_id", nullable = false)
    private Long listTypeId;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "value_code", length = 100, nullable = false)
    private String valueCode;

    @Column(name = "label_fr", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelEn;

    @Column(name = "sort_order", nullable = false, columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false, columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_system", nullable = false, columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean isSystem = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
