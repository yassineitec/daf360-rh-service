package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * A recent joiner as shown on the RH dashboard.
 *
 * <p>{@code docsPresent} / {@code docsRequired} / {@code missingDocs} let the card
 * render an onboarding-completeness bar. They are derived from
 * {@code [dbo].[employee_documents]} using the same three required types and the
 * same "not REJECTED" rule as {@code /dashboard/missing-documents}, because there
 * is no onboarding <em>task</em> data to read: onboarding only ever writes a single
 * {@code [dbo].[workflow_instances]} row (event_type = 'ONBOARDING'), and the only
 * task tables in this service are the offboarding ones.
 */
public record NouvelEmployeDto(
        Long         profileId,
        String       fullName,
        String       photoUrl,
        LocalDate    hireDate,
        String       department,
        String       grade,
        String       gender,
        boolean      onboardingCompleted,
        String       paysLabel,
        String       discipline,
        String       contractType,
        int          docsPresent,
        int          docsRequired,
        List<String> missingDocs,
        /** Per-section onboarding completeness, in wizard-step order. */
        List<OnboardingSectionDto> onboardingSections
) {}
