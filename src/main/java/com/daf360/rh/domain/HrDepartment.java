package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Maps the [dbo].[departments] dimension table (V23 migration).
 * Not to be confused with the legacy [dbo].[departements] table mapped by {@link Department}.
 */
@Entity
@Table(name = "departments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrDepartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "code", nullable = false, length = 50, columnDefinition = "nvarchar(50)")
    private String code;

    @Column(name = "label_fr", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 100, columnDefinition = "nvarchar(100)")
    private String labelEn;

    /** Nullable — references parent department id for hierarchical structure. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
