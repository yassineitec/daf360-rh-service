package com.daf360.rh.dto;

public record UserForSyncDto(
        Long id,
        String azureOid,
        String fullName,
        String email,
        Long paysId,
        String roleName,
        Boolean isActive
) {}
