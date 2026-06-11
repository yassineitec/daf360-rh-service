package com.daf360.rh.dto.profile;

import lombok.Data;

@Data
public class ProfileFilterDto {
    private Long   paysId;
    private String status;       // LifecycleStatus enum value
    private String department;
    private String grade;
    private String contract;     // contract_type value
    private String search;       // free-text — matches fullName / email via Users join
}
