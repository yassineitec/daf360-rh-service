package com.daf360.rh.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRoutingRecipientRepository extends JpaRepository<NotificationRoutingRecipient, Long> {

    List<NotificationRoutingRecipient> findByRuleIdAndIsActiveTrue(Long ruleId);
}
