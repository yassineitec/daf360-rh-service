package com.daf360.rh.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailRoutingRecipientRepository extends JpaRepository<EmailRoutingRecipient, Long> {

    List<EmailRoutingRecipient> findByRuleIdAndIsActiveTrue(Long ruleId);
}
