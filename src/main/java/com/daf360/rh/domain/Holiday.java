package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps [holidays] in DAF360_HR.
 * Schema verified 2026-05-31: created_at / updated_at / deleted_at are datetime2 (NOT datetimeoffset).
 * FK → pays.id via pays_id.
 */
@Entity
@Table(name = "holidays")
@SQLRestriction("deleted = 0 OR deleted IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date_holiday", nullable = false)
    private LocalDate dateHoliday;

    @Column(name = "french_label", nullable = false, length = 255)
    private String frenchLabel;

    @Column(name = "english_label", nullable = false, length = 255)
    private String englishLabel;

    @Column(name = "is_recurring")
    private Boolean isRecurring;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "deleted")
    private Boolean deleted;

    /** datetime2 — verified from DB, not datetimeoffset. */
    @Column(name = "created_at", updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetime2")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at", columnDefinition = "datetime2")
    private LocalDateTime deletedAt;
}
