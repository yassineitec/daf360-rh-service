package com.daf360.rh.service.sharepoint;

/**
 * Pure path arithmetic for the SharePoint integration. No Graph, no DB — so every rule
 * below is unit-testable, which matters because getting one of them wrong writes files
 * into the wrong place in HR's real tree.
 */
public final class SharePointPaths {

    /** The literal placeholder used in {@code pays.photo_sharepoint_location} and
     *  {@code document_templates.sharepoint_location}. Not a Handlebars token. */
    public static final String EMPLOYEE_FOLDER_TOKEN = "{employeeFolder}";

    private SharePointPaths() {}

    /**
     * Comparison form of a single path segment: trimmed, interior whitespace runs
     * collapsed to one space.
     *
     * <p>This exists because SharePoint and {@code Users.fullName} do not agree on
     * spacing. The real tree holds {@code "Abir  ESSAYEM"} with two spaces, while
     * {@link EmployeeFolderResolver#normalize} collapses that to one — so a literal
     * lookup missed the folder and {@code mkdir -p} cheerfully created a second,
     * near-identical one beside it. Matching on the squashed form finds the folder
     * that is actually there.
     */
    public static String squash(String segment) {
        return segment == null ? "" : segment.trim().replaceAll("\\s+", " ");
    }

    /** Whether two path segments name the same folder, ignoring case and spacing. */
    public static boolean sameSegment(String a, String b) {
        return squash(a).equalsIgnoreCase(squash(b));
    }

    /**
     * The per-employee root of a country's tree, derived from any configured location
     * template: everything up to and including the {@code {employeeFolder}} segment.
     *
     * <p>e.g. {@code "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents"}
     * → {@code "Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}"}
     *
     * <p>Deriving it is what lets uploaded documents reach SharePoint with **no new
     * column and no hand-applied migration** — rh-service has no Flyway, so every added
     * column is a manual step that can be forgotten on one server and silently disable
     * the feature there. The country's employee-folder root is already encoded in
     * {@code pays.photo_sharepoint_location}; only the leaf differs per document type.
     *
     * @return the base path, or null if the template is blank or carries no placeholder
     *         (in which case the caller must skip SharePoint entirely)
     */
    public static String employeeFolderBase(String locationTemplate) {
        if (locationTemplate == null || locationTemplate.isBlank()) return null;
        int at = locationTemplate.indexOf(EMPLOYEE_FOLDER_TOKEN);
        if (at < 0) return null;
        return locationTemplate.substring(0, at + EMPLOYEE_FOLDER_TOKEN.length());
    }

    /**
     * A file name safe to PUT at {@code .../root:/{path}:/content}.
     *
     * <p>Path separators are the dangerous ones — a name containing {@code /} would
     * silently retarget the upload into a different folder. The rest are the characters
     * SharePoint itself rejects in an item name. Runs of replaced characters collapse so
     * the result stays readable rather than turning into a row of underscores.
     */
    public static String safeFileName(String fileName, String fallback) {
        if (fileName == null || fileName.isBlank()) return fallback;
        String cleaned = fileName.trim()
                .replaceAll("[\\\\/:*?\"<>|#%]+", "_")
                .replaceAll("\\s+", " ");
        // Leading dots/spaces make an item SharePoint refuses to create.
        cleaned = cleaned.replaceAll("^[.\\s]+", "");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    /** Joins path segments with '/', skipping blanks so no empty segment ever appears. */
    public static String join(String... segments) {
        StringBuilder out = new StringBuilder();
        for (String s : segments) {
            if (s == null || s.isBlank()) continue;
            String trimmed = s.replaceAll("^/+|/+$", "");
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(trimmed);
        }
        return out.toString();
    }
}
