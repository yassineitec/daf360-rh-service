package com.daf360.rh.repository;

import com.daf360.rh.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Merges the event-driven, call-site-populated [audit_log] with the generic,
 * trigger-written [audit_trail] into one timeline for AuditController.
 * Declared against AuditLog as its nominal entity type only because Spring Data
 * repositories need one -- this repository's actual query spans both tables.
 *
 * ts is normalised to a plain (UTC) DATETIME2, not DATETIMEOFFSET: the MSSQL JDBC driver
 * returns its own microsoft.sql.DateTimeOffset for DATETIMEOFFSET columns, which Spring
 * Data's native-query interface projections cannot auto-convert to OffsetDateTime
 * ("Target type is not an interface and no matching Converter found") -- confirmed by
 * actually calling the endpoint, not just by reading the code. DATETIME2 maps cleanly to
 * LocalDateTime via plain JDBC.
 */
public interface AuditQueryRepository extends Repository<AuditLog, Long> {

    @Query(value = """
        SELECT * FROM (
            SELECT id,
                   CAST('EVENT_LOG' AS VARCHAR(10)) AS source,
                   CAST(userId AS NVARCHAR(200)) AS actor,
                   CAST(action AS NVARCHAR(50)) AS action,
                   CAST(entityType AS NVARCHAR(100)) AS entityType,
                   CAST(entityId AS NVARCHAR(400)) AS entityId,
                   CAST(oldValue AS NVARCHAR(MAX)) AS oldValue,
                   CAST(newValue AS NVARCHAR(MAX)) AS newValue,
                   CAST(ipAddress AS NVARCHAR(50)) AS ip,
                   CAST(SWITCHOFFSET(timestamp, '+00:00') AS DATETIME2) AS ts
            FROM audit_log
            UNION ALL
            SELECT id,
                   CAST('GENERIC' AS VARCHAR(10)) AS source,
                   CAST(changed_by AS NVARCHAR(200)) AS actor,
                   CAST(CASE action WHEN 'I' THEN 'CREATE' WHEN 'U' THEN 'UPDATE' WHEN 'D' THEN 'DELETE' ELSE action END AS NVARCHAR(50)) AS action,
                   CAST(table_name AS NVARCHAR(100)) AS entityType,
                   CAST(pk_json AS NVARCHAR(400)) AS entityId,
                   CAST(old_value AS NVARCHAR(MAX)) AS oldValue,
                   CAST(new_value AS NVARCHAR(MAX)) AS newValue,
                   CAST(NULL AS NVARCHAR(50)) AS ip,
                   changed_at AS ts
            FROM audit_trail
        ) combined
        ORDER BY ts DESC
        """,
        countQuery = "SELECT (SELECT COUNT(*) FROM audit_log) + (SELECT COUNT(*) FROM audit_trail)",
        nativeQuery = true)
    Page<AuditEntryView> findMerged(Pageable pageable);
}
