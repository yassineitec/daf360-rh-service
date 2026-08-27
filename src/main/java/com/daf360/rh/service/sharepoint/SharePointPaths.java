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

    /** The literal placeholder for a year folder, used by year-scoped kinds (see
     *  {@link DocKind#isYearScoped()}) — e.g. {@code ".../01_Pay-Slip/{year}"}. */
    public static final String YEAR_TOKEN = "{year}";

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

    /**
     * {@link #squash} plus diacritic folding: {@code "Kods CHÉRIF"} and {@code "Kods CHERIF"}
     * reduce to the same form.
     *
     * <p>Needed because the live tree is inconsistent about accents — {@code "Kods CHÉRIF"}
     * sits beside an unaccented {@code "Linda CHERIF"} and {@code "Yassine CHERIF"} — so
     * whichever spelling {@code Users.fullName} holds, one of the two forms would miss under
     * a plain case-insensitive compare, and the employee's documents would be invisible with
     * the folder sitting right there.
     *
     * <p>Folding is a canonical transform, not a similarity measure. That distinction is the
     * whole safety argument: {@code "ZEDINI"} and {@code "ZEDDINI"} still do NOT match, and
     * they must not — edit-distance matching on an employee folder can only fail by handing
     * someone another employee's documents.
     */
    public static String fold(String segment) {
        String squashed = squash(segment);
        if (squashed.isEmpty()) return squashed;
        return java.text.Normalizer.normalize(squashed, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    /** Whether two path segments name the same folder, ignoring case, spacing and accents. */
    public static boolean sameSegment(String a, String b) {
        return fold(a).equalsIgnoreCase(fold(b));
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

    /**
     * The part of a path BEFORE a token, trailing separator removed — the complement of
     * {@link #employeeFolderBase}, which cuts inclusively.
     *
     * <p>e.g. {@code ".../{employeeFolder}/01_Pay-Slip/{year}"} → {@code ".../01_Pay-Slip"}
     *
     * <p>Needed because a year-scoped kind cannot be existence-checked at its full path: the
     * year folder for the current year may legitimately not exist yet (nobody has been paid
     * in it), which is a normal empty result, not a misconfiguration. The folder that must
     * exist is the one holding the year folders, and that is what this returns.
     *
     * @return the path unchanged when the token is absent
     */
    public static String stripFrom(String path, String token) {
        if (path == null || token == null) return path;
        int at = path.indexOf(token);
        if (at < 0) return path;
        return path.substring(0, at).replaceAll("/+$", "");
    }

    /**
     * Whether a path is safe to hand to Graph from a client-supplied value.
     *
     * <p>The admin folder browser takes a path from the request and interpolates it straight
     * into a Graph URL. That is the one place in this integration where an outside string
     * reaches HR's whole document tree, so the rules are deliberately narrow rather than
     * clever: an empty path (the drive root) is fine, and anything else must be plain
     * {@code segment/segment} with no traversal, no separator tricks and no control characters.
     *
     * <p>Rejects rather than sanitises. A path that has to be cleaned up before use is a path
     * the caller did not mean, and silently correcting it is how a browser ends up listing
     * somewhere nobody asked for.
     */
    public static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty()) return true; // drive root
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) return false;
        if (path.contains("\\") || path.contains(":")) return false;
        for (String segment : path.split("/")) {
            if (segment.isBlank()) return false;
            if (segment.equals(".") || segment.equals("..")) return false;
            // Leading/trailing dots and spaces are names SharePoint itself refuses, and a
            // control character has no business in a folder name.
            if (!segment.equals(segment.trim())) return false;
            for (char c : segment.toCharArray()) {
                if (c < 0x20 || c == 0x7F) return false;
            }
        }
        return true;
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
