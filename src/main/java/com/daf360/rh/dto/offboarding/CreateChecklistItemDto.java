package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Adds a line to a checklist.
 *
 * Exists for HANDOVER above all: every departure hands over different work — clients,
 * repositories, keys to a cupboard — so that list cannot be seeded from a catalog the way
 * ACCESS and KIT are. `itemCode` is generated server-side; the caller only supplies a label.
 */
@Data
public class CreateChecklistItemDto {

    /** HANDOVER | ACCESS | KIT. The group decides which permission may add to it. */
    @NotBlank
    private String group;

    @NotBlank
    @Size(max = 255)
    private String label;
}
