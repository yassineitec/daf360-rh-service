package com.daf360.rh.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRoutingRuleRepository extends JpaRepository<NotificationRoutingRule, Long> {

    Optional<NotificationRoutingRule> findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(String eventCode, Long paysId);

    Optional<NotificationRoutingRule> findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(String eventCode);

    List<NotificationRoutingRule> findAllByIsActiveTrueOrderByEventTypeEventCodeAsc();

    Optional<NotificationRoutingRule> findByEventTypeIdAndIsActiveTrue(Long eventTypeId);
}
