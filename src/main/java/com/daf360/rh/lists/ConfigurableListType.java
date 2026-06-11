package com.daf360.rh.lists;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "configurable_list_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurableListType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 100, nullable = false)
    private String code;

    @Column(name = "label_fr", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelEn;

    @Column(name = "description", columnDefinition = "NVARCHAR(500)")
    private String description;

    @Column(name = "is_per_pays", nullable = false, columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean isPerPays = false;

    @Column(name = "is_system", nullable = false, columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean isSystem = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
