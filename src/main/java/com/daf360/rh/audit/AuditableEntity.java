package com.daf360.rh.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Base class for rh-service-owned tables (employees, contracts, etc.).
 * Uses OffsetDateTime to match the datetimeoffset type used in DAF360_HR.
 *
 * Dates are set via @PrePersist / @PreUpdate (JPA lifecycle callbacks) instead of
 * Spring Data's @CreatedDate / @LastModifiedDate because Spring Data Commons 4.0.4
 * does not support OffsetDateTime as an auditing date type.
 *
 * NOTE: do NOT extend this for entities that map to pre-existing Timesheet/Portal tables
 * (absences, audit_log, employee_profiles…) — those tables have their own fixed schemas.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @PrePersist
    protected void onPrePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Europe/Paris"));
        if (this.createdAt == null) this.createdAt = now;
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneId.of("Europe/Paris"));
    }
}
