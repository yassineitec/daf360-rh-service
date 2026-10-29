package com.daf360.rh.dto;

public record UserForSyncDto(
        Long id,
        String azureOid,
        String fullName,
        String email,
        Long paysId,
        String roleName,
        Boolean isActive,
        /**
         * False for a test, duplicate or machine account.
         *
         * Carried to the consuming services rather than filtered out of this feed: they
         * mirror ACCOUNTS and resolve real traffic against the copy, so a missing row could
         * break a foreign key. They filter it in their own PICKERS instead.
         */
        Boolean isEmployee,
        /**
         * The account's role id. Last, and additive: the consumers that only read roleName
         * (finance, payroll) ignore it; the timesheet replicates Roles and needs the id to
         * restrict task categories by role.
         */
        Long roleId
) {}
