package com.daf360.rh.dto;

/**
 * One entity (pays) as replicated into the consuming services' shadow tables.
 *
 * `devise` has no column in `[dbo].[pays]` — RH does not carry a currency per entity —
 * so it is always null here. The field is kept in the contract because the payroll
 * service's `pays_ref.devise` is NOT NULL and its sync fills the gap from its own
 * `app.fx-rates` configuration; dropping the field would silently turn that into a
 * missing key rather than an explicit null.
 */
public record PaysForSyncDto(
        Long id,
        String isoCode,
        String frenchLabel,
        String devise
) {}
