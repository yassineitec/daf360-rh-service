package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "grades")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Grade {

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

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * Default préavis in calendar days for this grade (V64) — the figure a negotiation
     * starts from, not the one that applies. The agreed value is frozen on the contract
     * (see EmployeeContract.noticePeriodDays); this is only its default and the fallback
     * for contracts created before V69.
     *
     * Null = no default agreed for this grade. Never coerce it to 0: "not configured" and
     * "no préavis owed" are different answers and only one of them is safe to display.
     */
    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
