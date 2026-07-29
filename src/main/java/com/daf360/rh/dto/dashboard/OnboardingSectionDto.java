package com.daf360.rh.dto.dashboard;

/**
 * How complete one section of an employee's onboarding file is.
 *
 * <p>The sections mirror the onboarding wizard's steps (see the frontend's
 * {@code onboarding.model.ts} {@code STEPS}), so "Banque is 1/2" on the dashboard means
 * the same thing as the Banque step looking half-filled in the wizard.
 *
 * <p>{@code key} is a stable code (IDENTITY, CONTRACT, …) the frontend translates —
 * never a display label, so labels stay localisable.
 */
public record OnboardingSectionDto(
        String key,
        int    filled,
        int    total
) {}
