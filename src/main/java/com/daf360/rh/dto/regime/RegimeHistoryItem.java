package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class RegimeHistoryItem {
    private Long id;
    private String assignmentLevel;
    private Long oldRegimeId;
    private String oldRegimeLabelFr;
    private Long newRegimeId;
    private String newRegimeLabelFr;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String reason;
    private Long changedBy;
    private OffsetDateTime changedAt;
}
