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
}
