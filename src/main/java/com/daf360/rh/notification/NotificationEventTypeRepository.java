package com.daf360.rh.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationEventTypeRepository extends JpaRepository<NotificationEventType, Long> {

    List<NotificationEventType> findByIsActiveTrueOrderByLabelFrAsc();

    Optional<NotificationEventType> findByEventCode(String eventCode);
}
