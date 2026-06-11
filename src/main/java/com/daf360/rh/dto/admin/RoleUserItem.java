package com.daf360.rh.dto.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoleUserItem {
    private Long   userId;
    private String fullName;
    private String email;           // Users.username
    private Long   paysId;
    private String paysLabel;       // pays.french_label
    private String currentRoleName; // populated only in search results
}
