package com.daf360.rh.domain.enums;

/**
 * Category of a request type — drives post-approval automation.
 *
 * DOCUMENT           → triggers DocumentGenerationService
 * PERSONAL_DATA_CHANGE → triggers profile field update
 * BANK_DETAILS       → requires L2 Finance Officer co-approval
 * CAREER             → informational, manual follow-up
 * OTHER              → no automated action
 */
public enum RequestCategory {
    DOCUMENT,
    PERSONAL_DATA_CHANGE,
    BANK_DETAILS,
    CAREER,
    OTHER
}
