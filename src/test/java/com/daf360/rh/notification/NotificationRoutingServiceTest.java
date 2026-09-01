package com.daf360.rh.notification;

import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for NotificationRoutingService.
 * No Spring context — all collaborators mocked with Mockito.
 * @Async on resolveAndDispatch() is a Spring proxy concern; without a Spring
 * container it executes synchronously, which is exactly what we need.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRoutingServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock NotificationRoutingRuleRepository       ruleRepo;
    @Mock NotificationRoutingRecipientRepository  recipientRepo;
    @Mock EmailRoutingRecipientRepository         emailRecipientRepo;
    @Mock MailService                             mailService;
    @Mock AuditService                            auditService;
    @Mock JdbcTemplate                            jdbc;
    @Mock InAppNotifier                           inAppNotifier;

    @InjectMocks NotificationRoutingService service;

    // ── Shared fixtures ───────────────────────────────────────────────────────

    private NotificationEventType globalType;
    private NotificationRoutingRule globalRule;
    private NotificationRoutingRule entityRule;

    private static final String EVENT_CODE = "CANDIDATE_ACCEPTED";
    private static final Long   PAYS_ID    = 179L;

    @BeforeEach
    void setUp() {
        globalType = NotificationEventType.builder()
                .id(1L)
                .eventCode(EVENT_CODE)
                .supportsEmail(true)
                .module("HR")
                .build();

        globalRule = NotificationRoutingRule.builder()
                .id(1L)
                .paysId(null)
                .sendInapp(true)
                .sendEmail(false)
                .inappTitleTemplate("Candidat {candidateName}")
                .inappBodyTemplate("Action pour {candidateName}")
                .eventType(globalType)
                .isActive(true)
                .build();

        entityRule = NotificationRoutingRule.builder()
                .id(2L)
                .paysId(PAYS_ID)
                .sendInapp(true)
                .sendEmail(false)
                .inappTitleTemplate("Candidat {candidateName}")
                .inappBodyTemplate("Action pour {candidateName}")
                .eventType(globalType)
                .isActive(true)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RoutingContext ctxForPays(Long paysId) {
        return RoutingContext.builder()
                .eventCode(EVENT_CODE)
                .paysId(paysId)
                .build();
    }

    // ── Test 1: entity-specific rule overrides global ─────────────────────────

    @Test
    void resolveAndDispatch_entityRuleOverridesGlobal() {
        // Given
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(entityRule));
        when(recipientRepo.findByRuleIdAndIsActiveTrue(entityRule.getId()))
                .thenReturn(Collections.emptyList());

        // When
        service.resolveAndDispatch(ctxForPays(PAYS_ID));

        // Then — entity-specific lookup called once with correct args
        verify(ruleRepo, times(1))
                .findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID);

        // Global fallback must NOT be consulted because an entity rule was found
        verify(ruleRepo, never())
                .findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(anyString());
    }

    // ── Test 2: falls back to global when no entity rule ─────────────────────

    @Test
    void resolveAndDispatch_fallsBackToGlobalWhenNoEntityRule() {
        // Given
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.empty());
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(EVENT_CODE))
                .thenReturn(Optional.of(globalRule));
        when(recipientRepo.findByRuleIdAndIsActiveTrue(globalRule.getId()))
                .thenReturn(Collections.emptyList());

        // When
        service.resolveAndDispatch(ctxForPays(PAYS_ID));

        // Then — global lookup was reached
        verify(ruleRepo, times(1))
                .findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(EVENT_CODE);

        // No recipients → the notifier is handed an empty list, so no rows are written
        ArgumentCaptor<Collection<Long>> emptyCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(inAppNotifier).notifyUsers(
                emptyCaptor.capture(), anyString(), anyString(), anyString(), any());
        assertThat(emptyCaptor.getValue()).isEmpty();
    }

    // ── Test 3: no rule found — fails silently ────────────────────────────────

    @Test
    void resolveAndDispatch_noRuleFound_failsSilently() {
        // Given — both lookups return empty
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(anyString(), any()))
                .thenReturn(Optional.empty());
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(anyString()))
                .thenReturn(Optional.empty());

        // When / Then — must not throw
        assertThatNoException().isThrownBy(() -> service.resolveAndDispatch(ctxForPays(PAYS_ID)));

        // No side-effects
        verify(inAppNotifier, never()).notifyUsers(any(), any(), any(), any(), any());
        verify(mailService, never()).sendRoutedEmail(any(), any(), any(), any(), any());
    }

    // ── Test 4a: a SUBJECT recipient resolves to the event's subject ───────────

    @Test
    void resolveAndDispatch_subjectRecipient_resolvesToTheSubject() {
        final Long SUBJECT_USER = 500L;

        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(entityRule));   // sendInapp=true

        NotificationRoutingRecipient subjectRecipient = NotificationRoutingRecipient.builder()
                .id(9L)
                .recipientMode("SUBJECT")
                .isActive(true)
                .build();
        when(recipientRepo.findByRuleIdAndIsActiveTrue(entityRule.getId()))
                .thenReturn(List.of(subjectRecipient));
        // The subject is looked up, not trusted: an inactive user must not be notified.
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(SUBJECT_USER)))
                .thenReturn(List.of(SUBJECT_USER));

        service.resolveAndDispatch(RoutingContext.builder()
                .eventCode(EVENT_CODE)
                .paysId(PAYS_ID)
                .subjectUserId(SUBJECT_USER)
                .build());

        ArgumentCaptor<Collection<Long>> recipientsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(inAppNotifier, times(1)).notifyUsers(
                recipientsCaptor.capture(), anyString(), anyString(), anyString(), any());
        assertThat(recipientsCaptor.getValue()).containsExactly(SUBJECT_USER);

        // The configured recipients ARE consulted now. The old directUserId short-circuit
        // returned before this call, which is exactly what made the admin screen's
        // Destinataires section dead for subject-driven events.
        verify(recipientRepo).findByRuleIdAndIsActiveTrue(entityRule.getId());
    }

    // ── Test 4b: subject fallback when the rule has no recipients at all ──────

    @Test
    void resolveAndDispatch_noRecipientsButSubject_fallsBackToSubject() {
        final Long SUBJECT_USER = 501L;

        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(entityRule));
        when(recipientRepo.findByRuleIdAndIsActiveTrue(entityRule.getId()))
                .thenReturn(Collections.emptyList());

        service.resolveAndDispatch(RoutingContext.builder()
                .eventCode(EVENT_CODE)
                .paysId(PAYS_ID)
                .subjectUserId(SUBJECT_USER)
                .build());

        // Safety net for the window between deploying the code and applying V93: without it,
        // removing the short-circuit would silently stop telling employees their own request
        // was decided.
        ArgumentCaptor<Collection<Long>> recipientsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(inAppNotifier, times(1)).notifyUsers(
                recipientsCaptor.capture(), anyString(), anyString(), anyString(), any());
        assertThat(recipientsCaptor.getValue()).containsExactly(SUBJECT_USER);
    }

    // ── Test 5: sendEmail=false — no email sent ───────────────────────────────

    @Test
    void resolveAndDispatch_sendEmailFalse_noEmailSent() {
        // globalRule has sendEmail=false already
        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(globalRule));
        when(recipientRepo.findByRuleIdAndIsActiveTrue(globalRule.getId()))
                .thenReturn(Collections.emptyList());

        service.resolveAndDispatch(ctxForPays(PAYS_ID));

        verify(mailService, never()).sendRoutedEmail(any(), any(), any(), any(), any());
    }

    // ── Test 6: sendEmail=true but empty TO list — email not sent ────────────

    @Test
    void resolveAndDispatch_emptyToList_emailNotSent() {
        // Build a rule with email enabled
        NotificationRoutingRule emailRule = NotificationRoutingRule.builder()
                .id(3L)
                .paysId(PAYS_ID)
                .sendInapp(false)
                .sendEmail(true)
                .inappTitleTemplate("T")
                .inappBodyTemplate("B")
                .emailSubjectTemplate("Subject")
                .emailBodyTemplate("<p>Body</p>")
                .eventType(globalType)   // supportsEmail=true
                .isActive(true)
                .build();

        final Long TO_ROLE_ID = 99L;
        EmailRoutingRecipient toRecipient = EmailRoutingRecipient.builder()
                .id(1L)
                .roleId(TO_ROLE_ID)
                .recipientField("TO")
                .isActive(true)
                .build();

        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(emailRule));
        when(emailRecipientRepo.findByRuleIdAndIsActiveTrue(emailRule.getId()))
                .thenReturn(List.of(toRecipient));

        // DB returns no email addresses for that role
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TO_ROLE_ID), eq(PAYS_ID)))
                .thenReturn(Collections.emptyList());

        service.resolveAndDispatch(ctxForPays(PAYS_ID));

        // TO list is empty → email must NOT be sent
        verify(mailService, never()).sendRoutedEmail(any(), any(), any(), any(), any());
    }

    // ── Test 7: template placeholders are resolved before INSERT ──────────────

    @Test
    void resolveAndDispatch_templatePlaceholdersResolved() {
        final Long ROLE_ID  = 10L;
        final Long USER_ID  = 42L;

        NotificationRoutingRule ruleWithPlaceholder = NotificationRoutingRule.builder()
                .id(4L)
                .paysId(PAYS_ID)
                .sendInapp(true)
                .sendEmail(false)
                .inappTitleTemplate("Bonjour {firstName}")
                .inappBodyTemplate("Message pour {firstName}")
                .eventType(globalType)
                .isActive(true)
                .build();

        NotificationRoutingRecipient roleRecipient = NotificationRoutingRecipient.builder()
                .id(1L)
                .roleId(ROLE_ID)
                .isActive(true)
                .build();

        when(ruleRepo.findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(EVENT_CODE, PAYS_ID))
                .thenReturn(Optional.of(ruleWithPlaceholder));
        when(recipientRepo.findByRuleIdAndIsActiveTrue(ruleWithPlaceholder.getId()))
                .thenReturn(List.of(roleRecipient));

        // Role lookup returns one user
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(ROLE_ID), eq(PAYS_ID)))
                .thenReturn(List.of(USER_ID));

        RoutingContext ctx = RoutingContext.builder()
                .eventCode(EVENT_CODE)
                .paysId(PAYS_ID)
                .templateVars(Map.of("firstName", "Alice"))
                .build();

        service.resolveAndDispatch(ctx);

        // The rendered title is notifyUsers' 3rd argument
        // (recipients, module, title, message, target).
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(inAppNotifier, times(1)).notifyUsers(
                anyCollection(), anyString(), titleCaptor.capture(), anyString(), any());

        assertThat(titleCaptor.getValue()).contains("Alice");
        assertThat(titleCaptor.getValue()).doesNotContain("{firstName}");
    }
}
