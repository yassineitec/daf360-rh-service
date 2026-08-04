package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

/**
 * One checklist line. Field names match `OffboardingChecklistItem` in the frontend model,
 * which already declares and binds them — note `group`, not `groupCode`.
 */
@Data
@Builder
public class OffboardingChecklistItemDto {

    private Long    id;
    /** HANDOVER | ACCESS | KIT — mapped from `group_code`. */
    private String  group;
    private String  code;
    private String  label;
    private Boolean isDone;
    private String  documentUrl;
    private String  completedByName;
    private Integer orderIndex;
}
