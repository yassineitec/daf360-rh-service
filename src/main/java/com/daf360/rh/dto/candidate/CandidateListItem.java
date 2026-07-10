package com.daf360.rh.dto.candidate;

import com.daf360.rh.domain.enums.CandidateStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class CandidateListItem {

    // --- legacy fields (kept for backward compat) ---
    private Long id;
    private String firstName;
    private String lastName;
    private String emailPersonal;
    private String appliedPosition;
    private String appliedGrade;
    private LocalDate expectedStartDate;
    private CandidateStatus status;
    private OffsetDateTime createdAt;

    // --- pipeline page fields ---
    private String fullName;
    private String initials;
    private int colorIndex;
    private String poste;
    private String stage;
    private Integer fitScore;
    private String applicationDate;

    // --- enriched card fields ---
    private Integer experienceYears;
    private String location;
    /** Resolved employment-type label (e.g. CDI / CDD) — set by CandidateService. */
    private String contractType;
}
