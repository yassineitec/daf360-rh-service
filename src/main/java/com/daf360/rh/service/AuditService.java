package com.daf360.rh.service;

import com.daf360.rh.domain.AuditLog;
import com.daf360.rh.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(String actorId, String action, String entityType, Long entityId,
                    String before, String after) {
        log(actorId, action, entityType, entityId, before, after, null);
    }

    @Async
    public void log(String actorId, String action, String entityType, Long entityId,
                    String before, String after, String ipAddress) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(actorId != null ? actorId : "SYSTEM")
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId != null ? entityId.toString() : null)
                    .oldValue(before)
                    .newValue(after)
                    .ipAddress(ipAddress)
                    .module("HR")
                    .timestamp(OffsetDateTime.now())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log for action={} entity={}: {}", action, entityType, e.getMessage());
        }
    }
}
