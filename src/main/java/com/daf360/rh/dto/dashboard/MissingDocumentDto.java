package com.daf360.rh.dto.dashboard;

import java.util.List;

public record MissingDocumentDto(
        Long         profileId,
        String       fullName,
        List<String> missingDocs,       // codes, e.g. ["CONTRACT", "ID_CARD"]
        List<String> missingDocLabels,  // same order, the country's label_fr (V121: list is configurable)
        String       urgency            // "HIGH" | "MEDIUM" | "LOW"
) {}
