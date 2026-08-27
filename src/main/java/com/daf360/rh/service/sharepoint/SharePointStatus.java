package com.daf360.rh.service.sharepoint;

/**
 * Why a SharePoint location did, or did not, resolve.
 *
 * <p>The point of this enum is that a miss is never just "null". Before it existed, every
 * failure path in the photo mirror returned null with no log line, so the only way to find
 * out why an employee's avatar was blank was to read the Graph tree by hand — which is
 * exactly how long it took to discover that nothing had ever requested that employee's photo.
 * Each value below maps to a distinct message and a distinct fix.
 */
public enum SharePointStatus {

    /** Folder resolved and confirmed present. */
    FOUND,

    /** No {@code sharepoint_locations} row for this (country, kind) — or the configured
     *  template failed {@link DocKind#validateTemplate}. Fix: configure the path. */
    NO_CONFIG,

    /** The employee folder was named, but no such folder exists in the tree. Fix: create or
     *  rename the folder, or record a MANUAL override pointing at the real one. */
    FOLDER_MISSING,

    /** Two or more folders match the employee's name once case, spacing and accents are
     *  folded, so choosing one would be a coin toss over whose documents get served.
     *  Deliberately refused. Fix: rename one of the folders. */
    AMBIGUOUS,

    /** Several employees in the country share this exact {@code fullName}, so the folder
     *  name cannot identify one of them. Fix: disambiguate in the HR data, or override. */
    AMBIGUOUS_EMPLOYEE,

    /** No usable {@code Users.fullName} for the employee, so no folder name to look for. */
    NO_NAME,

    /** SharePoint is unconfigured for this deployment ({@code MS_GRAPH_*} blank), or Graph
     *  itself failed. Distinguished from the above so an operator is not sent hunting for a
     *  data problem when the integration is simply switched off. */
    UNAVAILABLE
}
