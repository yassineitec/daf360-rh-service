package com.daf360.rh.dto;

/**
 * One role as replicated into the timesheet's `roles_ref` shadow table (task categories are
 * restricted to roles). `deleted` is carried rather than filtered: a category may still name a
 * deleted role, and the replica must keep the row its foreign key points at.
 */
public record RoleForSyncDto(
        Long id,
        String frenchName,
        String englishName,
        Long parentRoleId,
        Boolean deleted
) {}
