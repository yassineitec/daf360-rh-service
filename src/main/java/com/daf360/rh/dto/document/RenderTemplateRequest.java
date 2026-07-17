package com.daf360.rh.dto.document;

import lombok.Data;

@Data
public class RenderTemplateRequest {
    /** Employee profile to resolve {{employee.*}} variables. Null = use placeholder values. */
    private Long employeeProfileId;
}
