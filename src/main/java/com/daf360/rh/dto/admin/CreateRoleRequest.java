package com.daf360.rh.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateRoleRequest {

    @NotBlank
    private String frenchName;

    private Long parentRoleId;

    private Boolean showAll = false;

    private List<String> permissions;

    /** OWN (default) | LIST | ALL. OWN = each holder sees only their own country, which is
     *  what a role shared across countries wants. LIST = the paysScope below, same for all. */
    private String paysScopeMode;

    /** Countries this role may see. Only used when paysScopeMode is LIST. */
    private List<Long> paysScope;
}
