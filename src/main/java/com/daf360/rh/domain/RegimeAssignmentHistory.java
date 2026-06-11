package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.AssignmentLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "regime_assignment_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegimeAssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_level", nullable = false, length = 20)
    private AssignmentLevel assignmentLevel;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_regime_id")
    private WorkingTimeRegime oldRegime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_regime_id")
    private WorkingTimeRegime newRegime;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "reason", columnDefinition = "nvarchar(500)")
    private String reason;

    // changedBy — no User entity, store raw FK
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime changedAt;
}
