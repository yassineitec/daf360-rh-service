package com.daf360.rh.dto.recruitment;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Used by the candidate form to select a recruitment demand.
 *
 * {@code headcount} / {@code positionsFilled} (V137) let the option say how many posts are
 * left, so RH does not pick a vacancy that is about to close.
 *
 * {@code earlierRequests} / {@code archivedCandidates}: how many other demands were raised for
 * the same post (same department + title + exact title — the job library's rule) and how many
 * archived candidatures they left. When there are some, the creation wizard offers to start
 * from one of those candidates instead of from a blank form.
 */
@Data
@AllArgsConstructor
public class ApprovedDemandOption {
    private Long id;
    private String label;
    private String department;
    private int headcount;
    private int positionsFilled;
    private int earlierRequests;
    private int archivedCandidates;
}
