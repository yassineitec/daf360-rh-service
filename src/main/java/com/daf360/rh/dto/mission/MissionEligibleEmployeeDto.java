package com.daf360.rh.dto.mission;

/**
 * A person the caller may plan a mission for — one of their role descendants, in their own
 * pays. Same shape the RH frontend needs for the employee and responsable pickers.
 */
public record MissionEligibleEmployeeDto(
        Long id,
        String fullName,
        String email,
        String roleName,
        Long paysId
) {}
