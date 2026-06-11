package com.daf360.rh.dto.profile;

import com.daf360.rh.domain.enums.LifecycleStatus;
import lombok.Data;

import java.time.LocalDate;

/** Lightweight projection used in paginated list responses. */
@Data
public class EmployeeProfileSummaryDto {
    private Long            id;
    private Long            userId;
    private Long            paysId;
    private LifecycleStatus lifecycleStatus;
    private String          department;
    private String          grade;
    private String          contractType;
    private LocalDate       hireDate;
    private String          photoUrl;
}
