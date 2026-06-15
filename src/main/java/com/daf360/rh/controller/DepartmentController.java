package com.daf360.rh.controller;

import com.daf360.rh.dto.DepartmentResponseDto;
import com.daf360.rh.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/hr/departements")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentRepository departmentRepository;

    @GetMapping
    //@PreAuthorize("isAuthenticated()")
    public List<DepartmentResponseDto> list() {
        return departmentRepository.findAll().stream().map(d -> {
            DepartmentResponseDto dto = new DepartmentResponseDto();
            dto.setId(d.getId());
            dto.setName(d.getName());
            dto.setCode(d.getCode());
            dto.setManagerId(d.getManagerId());
            dto.setParentId(d.getParentId());
            return dto;
        }).toList();
    }
}
