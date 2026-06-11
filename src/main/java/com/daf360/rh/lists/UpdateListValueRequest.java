package com.daf360.rh.lists;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * PATCH payload — every field is optional.
 * Absent fields must be treated as "no change" in the service layer.
 *
 * NOTE: Must NOT use @Builder — Jackson 3.x requires a no-args constructor
 * to deserialize @RequestBody, and @Builder suppresses it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListValueRequest {

    @Size(max = 255)
    private String labelFr;

    @Size(max = 255)
    private String labelEn;

    private Integer sortOrder;

    private Boolean isActive;

    /** When {@code true} the service bypasses the is-system guard on deactivation. */
    private Boolean forceDeactivate;   // null = false (service uses Boolean.TRUE.equals check)
}
