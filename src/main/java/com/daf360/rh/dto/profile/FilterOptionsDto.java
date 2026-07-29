package com.daf360.rh.dto.profile;

import java.util.List;

/**
 * Dropdown values for the /rh/profiles filter panel.
 *
 * Each entry carries the {@code value} the list endpoint expects alongside the
 * {@code label} to display — they are not always the same. `pays` filters by
 * numeric id ({@code Users.pays_id}) while department / grade filter by their
 * French label, and returning bare labels for all three is what made the
 * country filter fail: the client sent "Tunisie" into a {@code Long} param.
 *
 * `contractTypes` carries raw codes only ({@code employee_profiles.contract_type}
 * is a free varchar holding two generations of codes); the client maps them
 * through PROFILES.CONTRACT_TYPE.* and falls back to the code itself.
 */
public record FilterOptionsDto(
        List<FilterOptionDto> departments,
        List<FilterOptionDto> grades,
        List<FilterOptionDto> pays,
        List<String>          contractTypes
) {
    public record FilterOptionDto(String value, String label) {}
}
