package com.daf360.rh.dto.candidate;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CandidateHistoryItem {
    private Long   id;
    private String timestamp;
    private String action;
    private String actionLabel;
    private Long   performedByUserId;
    private String performedByName;
    private String comment;
    private String resultingStatus;
}
