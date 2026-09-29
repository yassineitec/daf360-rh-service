package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Create or update a leave type.
 *
 * `code` is the stable key every filed request stores, so it is write-once: the update path
 * ignores it. Changing a code would orphan the history filed under it, and no screen should
 * make that a two-character edit.
 */
@Data
public class AbsenceTypeUpsert {

    /**
     * Uppercase letters, digits and underscore. Constrained because this value reaches a
     * URL, a filter parameter and 716 stored rows — and because a code with a space or an
     * accent is a lifetime of quoting bugs.
     */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "Le code doit être en MAJUSCULES, chiffres et _ uniquement.")
    private String code;

    @NotBlank @Size(max = 255)
    private String labelFr;

    @NotBlank @Size(max = 255)
    private String labelEn;

    private boolean active = true;

    /**
     * Whether approving debits an allowance. Meaningless without `balanceField`, and the
     * service rejects the pair being half-set rather than creating a type that looks like it
     * tracks a balance and silently does not.
     */
    private boolean tracksBalance = false;

    /** CONGE | MALADIE | TELETRAVAIL — the Users column the debit lands on. */
    private String balanceField;

    private boolean includedInHrStats = false;
    private boolean requiresJustification = false;

    @Min(1)
    private Integer maxDays;

    @Min(0)
    private int displayOrder = 0;

    /** Stored, not enforced — see AbsenceTypeDto. */
    private String allowedGender;

    private boolean managerCanView = true;

    /** Stored, not enforced — see ApproverResolutionStrategy. */
    private String approverResolutionStrategy;

    /**
     * Role ids allowed to approve this type. Empty or null clears the restriction, which
     * makes the generic manager hierarchy apply again.
     */
    private List<Long> approverRoleIds;
}
