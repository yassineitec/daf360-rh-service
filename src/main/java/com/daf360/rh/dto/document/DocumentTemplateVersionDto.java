package com.daf360.rh.dto.document;

import java.time.OffsetDateTime;

public record DocumentTemplateVersionDto(
        Long id,
        Long templateId,
        int versionNumber,
        String htmlContent,
        Long changedBy,
        OffsetDateTime changedAt,
        String changeSummary) {
}
