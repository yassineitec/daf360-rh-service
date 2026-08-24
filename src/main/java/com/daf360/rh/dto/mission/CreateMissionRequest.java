package com.daf360.rh.dto.mission;

import com.daf360.rh.domain.enums.MissionScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * What a manager fills in when planning a mission.
 *
 * Not on this DTO, deliberately: the status, the pays and the author. The status is always
 * PENDING_HR, the pays is read from the employee and the author from the JWT — accepting
 * any of the three from the client would let a manager plan a mission for someone else's
 * team, or drop a pre-approved one straight into finance's queue.
 */
@Data
public class CreateMissionRequest {

    /** Users.id of the traveller. Must be a descendant of the caller's role. */
    @NotNull
    private Long employeeUserId;

    /** A colleague. Either this or {@code responsableName} is required. */
    private Long responsableUserId;

    @Size(max = 255)
    private String responsableName;

    @NotBlank
    @Size(max = 255)
    private String title;

    private String details;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @NotNull
    private MissionScope scope;

    /** Set when the destination country is one of ours; {@code countryLabel} otherwise. */
    private Long destinationPaysId;

    @Size(max = 120)
    private String countryLabel;

    @NotBlank
    @Size(max = 120)
    private String city;

    @Size(max = 500)
    private String address;
}
