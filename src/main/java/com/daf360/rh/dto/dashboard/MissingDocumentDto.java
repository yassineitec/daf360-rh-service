package com.daf360.rh.dto.dashboard;

import java.util.List;

public record MissingDocumentDto(
        Long         profileId,
        String       fullName,
        List<String> missingDocs,   // e.g. ["CONTRACT", "ID_CARD"]
        String       urgency        // "HIGH" | "MEDIUM" | "LOW"
) {}
