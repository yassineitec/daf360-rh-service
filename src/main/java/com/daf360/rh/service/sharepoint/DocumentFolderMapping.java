package com.daf360.rh.service.sharepoint;

import java.util.Map;

/**
 * Which subfolder of an employee's SharePoint folder an uploaded document belongs in.
 *
 * <p>The four target names below are the REAL ones, listed from the live tree
 * (`Tunisia/01_HR/01_Contracts-Employment/<employee>/`), not guessed:
 * <pre>
 *   Employment Contracts &amp; Amendments
 *   Evaluations &amp; Performance Reviews
 *   HR Requests                        ← generated attestations already land here
 *   Identity Documents                 ← the profile photo already lands here
 *   Time Off &amp; Leaves
 * </pre>
 *
 * <p>Five of the sixteen document types have no natural home among those
 * ({@code CV}, {@code DIPLOMA}, {@code RIB}, {@code CNSS}, {@code TAX_FORM}) so they, and
 * anything unmapped, go to {@link #DEFAULT_SUBFOLDER} — a folder this app creates itself.
 * That is deliberate: filing a CV into "HR Requests" or "Identity Documents" would put our
 * guess inside a folder HR curates, and unpicking that means moving files by hand. A new
 * sibling folder is obvious, easy to reorganise, and never corrupts an existing one.
 *
 * <p>Changing where a type lands is a one-line edit here. Nothing else reads this map, and
 * nothing persists the resolved folder, so a correction takes effect on the next upload —
 * previously uploaded files stay where they were put.
 */
public final class DocumentFolderMapping {

    /** Unmapped types land here. Created on demand by the upload's mkdir -p. */
    public static final String DEFAULT_SUBFOLDER = "Administrative Documents";

    // "Evaluations & Performance Reviews" is intentionally absent: no code in
    // DOCUMENT_TYPES corresponds to an evaluation today. Add the constant and the entry
    // together when a type for it exists.
    private static final String CONTRACTS = "Employment Contracts & Amendments";
    private static final String IDENTITY  = "Identity Documents";
    private static final String LEAVES    = "Time Off & Leaves";

    /**
     * Keys are the codes in {@code EmployeeDocumentService.DOCUMENT_TYPES}; that set is
     * the validated vocabulary, so a key here that is not in it can never be reached.
     */
    private static final Map<String, String> BY_TYPE = Map.ofEntries(
            // Anything that changes the employment relationship, including how it ends.
            Map.entry("CONTRACT",         CONTRACTS),
            Map.entry("CONTRACT_SIGNED",  CONTRACTS),
            Map.entry("AMENDMENT",        CONTRACTS),
            Map.entry("RESIGNATION",      CONTRACTS),
            Map.entry("DISCHARGE",        CONTRACTS),

            // Who the person is. PHOTO joins them: the profile photo already mirrors here
            // as Photo.jpg, so a photo uploaded through the documents tab sits beside it
            // instead of in a second location.
            Map.entry("ID_CARD",          IDENTITY),
            Map.entry("PASSPORT",         IDENTITY),
            Map.entry("RESIDENCE_PERMIT", IDENTITY),
            Map.entry("PHOTO",            IDENTITY),

            // A sick note is the justification for an absence, so it files with leave.
            Map.entry("MEDICAL_CERTIFICATE", LEAVES));

    private DocumentFolderMapping() {}

    /** Never null — an unknown or null type resolves to {@link #DEFAULT_SUBFOLDER}. */
    public static String subfolderFor(String documentType) {
        if (documentType == null) return DEFAULT_SUBFOLDER;
        return BY_TYPE.getOrDefault(documentType.trim().toUpperCase(), DEFAULT_SUBFOLDER);
    }
}
