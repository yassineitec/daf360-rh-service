package com.daf360.rh.dto.leave;

/**
 * A {label, value} pair for a dropdown.
 *
 * The label is resolved server-side in the caller's language because the same enum labels
 * feed the HR Excel exports, which are rendered on the server. The UI is free to ignore the
 * label and translate the code through its own i18n keys.
 */
public record LeaveOptionDto(String label, String value) {}
