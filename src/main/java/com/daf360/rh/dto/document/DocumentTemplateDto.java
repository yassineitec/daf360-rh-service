package com.daf360.rh.dto.document;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class DocumentTemplateDto {
    private Long            id;
    private Long            paysId;
    private String          category;
    private String          name;
    private String          description;
    private String          htmlContent;
    private List<String>    variables;
    private String          pageSize;
    private Boolean         isActive;
    private OffsetDateTime  createdAt;
    private OffsetDateTime  updatedAt;
}
