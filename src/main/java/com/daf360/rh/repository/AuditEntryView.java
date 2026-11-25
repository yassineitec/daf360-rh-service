package com.daf360.rh.repository;

import java.time.LocalDateTime;

/** One row of the merged audit_log + audit_trail timeline -- see AuditQueryRepository. */
public interface AuditEntryView {
    Long getId();
    String getSource();
    String getActor();
    String getAction();
    String getEntityType();
    String getEntityId();
    String getOldValue();
    String getNewValue();
    String getIp();
    LocalDateTime getTs();
}
