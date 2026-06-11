package com.daf360.rh.lists;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ReorderRequest {

    /** IDs in the desired display order (position = index + 1). */
    @NotEmpty
    private List<Long> orderedIds;
}
