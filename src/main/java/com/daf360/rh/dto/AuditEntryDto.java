package com.daf360.rh.dto;

import com.daf360.rh.repository.AuditEntryView;

import java.time.LocalDateTime;

public record AuditEntryDto(
        Long id,
        String source,
        String actor,
        String action,
        String entityType,
        String entityId,
        String oldValue,
        String newValue,
        String ip,
        LocalDateTime timestamp
) {
    public static AuditEntryDto from(AuditEntryView v) {
        return new AuditEntryDto(v.getId(), v.getSource(), v.getActor(), v.getAction(),
                v.getEntityType(), v.getEntityId(), v.getOldValue(), v.getNewValue(), v.getIp(), v.getTs());
    }
}
