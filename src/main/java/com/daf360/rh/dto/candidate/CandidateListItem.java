package com.daf360.rh.dto.candidate;

import com.daf360.rh.domain.enums.CandidateStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class CandidateListItem {

    private Long id;
    private String firstName;
    private String lastName;
    private String emailPersonal;
    private String appliedPosition;
    private String appliedGrade;
    private String contractType;
    private LocalDate expectedStartDate;
    private CandidateStatus status;
    private OffsetDateTime createdAt;
}
