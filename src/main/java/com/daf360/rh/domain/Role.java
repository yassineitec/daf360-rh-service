package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * Maps the shared [Roles] table in DAF360_HR (Timesheet-owned — read-mostly).
 * HR service reads roles and manages permission assignments.
 * created_at / updated_at / deleted_at are datetimeoffset (verified from DB).
 */
@Entity
@Table(name = "Roles")
@SQLRestriction("deleted = 0 OR deleted IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Backtick quoting bypasses Hibernate's CamelCaseToUnderscoresNamingStrategy
    // so the actual DB column names (frenchName, showAll) are used as-is.
    @Column(name = "`frenchName`", length = 100)
    private String frenchName;

    @Column(name = "parent_role_id")
    private Long parentRoleId;

    @Column(name = "`showAll`")
    private Boolean showAll;

    @Column(name = "deleted")
    private Boolean deleted;

    @Column(name = "created_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime deletedAt;
}
