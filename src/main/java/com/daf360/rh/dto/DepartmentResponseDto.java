package com.daf360.rh.dto;

import lombok.Data;

@Data
public class DepartmentResponseDto {
    private Long id;
    private String name;
    private String code;
    private Long managerId;
    private Long parentId;
}
