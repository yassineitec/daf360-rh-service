package com.daf360.rh.dto.offboarding;

import lombok.Data;

import java.time.LocalDate;

/**
 * Stage 1 (Déclaration) of the offboarding wizard.
 *
 * Every field is optional and PATCH semantics are "replace what is present". Because a
 * JSON absent field and an explicit `null` both deserialize to `null` here, this DTO
 * CANNOT express "clear this value" — and that is deliberate: the stage is a form that
 * always posts its whole shape, and a partial payload silently blanking a colleague's
 * entry is the failure mode worth ruling out. Wrap a field in `Optional` (or add an
 * explicit clear flag) the day a caller genuinely needs to unset one.
 *
 * `triggerDate` and `departureReason` are NOT here: they are what was declared when the
 * file was opened, they are `NOT NULL`, and changing the reason after the fact would
 * invalidate the task list that was generated from it.
 */
@Data
public class UpdateDeclarationRequestDto {

    /** The negotiated departure date. Setting this is what completes the declaration. */
    private LocalDate lastWorkingDay;

    /** Trigger date + notice per the convention collective. May differ from the above. */
    private LocalDate theoreticalExitDate;

    /** Free label ("3 mois", "Aucun") — the notice period is per-pays convention. */
    private String noticePeriodLabel;

    private Boolean noticeWaiverRequested;

    /** URL + display name of the resignation letter, as returned by the document upload. */
    private String justificationDocumentUrl;
    private String justificationDocumentName;

    private String departureNotes;

    /** Reassigning the handover manager also moves the KNOWLEDGE_TRANSFER task's owner. */
    private Long handoverManagerProfileId;
}
