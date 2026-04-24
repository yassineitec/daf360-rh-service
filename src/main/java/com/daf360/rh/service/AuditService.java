package com.daf360.rh.service;

import com.daf360.rh.domain.AuditLog;
import com.daf360.rh.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(String actorId, String action, String entity, Long entityId,
                    String before, String after) {
        log(actorId, action, entity, entityId, before, after, null);
    }

    @Async
    public void log(String actorId, String action, String entity, Long entityId,
                    String before, String after, String ip) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actorId(actorId != null ? actorId : "SYSTEM")
                    .action(action)
                    .entity(entity)
                    .entityId(entityId)
                    .beforeValue(before)
                    .afterValue(after)
                    .ip(ip)
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }
}
