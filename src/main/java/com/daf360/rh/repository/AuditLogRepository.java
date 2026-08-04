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

    /**
     * The trail for a set of rows of one type.
     *
     * An offboarding file's history is spread across six entity types — the instance, its
     * tasks, asset returns, checklist items, settlement lines and the exit interview — and
     * `entityId` is only unique *within* a type (task 5 and asset 5 both exist). So the caller
     * queries per type and merges, rather than trying to express it as one IN clause.
     */
    java.util.List<AuditLog> findByEntityTypeAndEntityIdInOrderByTimestampDesc(
            String entityType, java.util.Collection<String> entityIds);
}
