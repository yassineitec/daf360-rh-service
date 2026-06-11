package com.daf360.rh.repository;

import com.daf360.rh.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Find all audit entries by actor (userId field → userId DB column). */
    Page<AuditLog> findByUserId(String userId, Pageable pageable);

    /** Find all entries for a given entity type. */
    Page<AuditLog> findByEntityType(String entityType, Pageable pageable);

    /** Date range query using datetimeoffset-compatible OffsetDateTime. */
    Page<AuditLog> findByTimestampBetween(OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}
