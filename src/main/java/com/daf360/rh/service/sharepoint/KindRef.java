package com.daf360.rh.service.sharepoint;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A document kind as the SharePoint machinery needs to know it: a CODE plus the one behaviour
 * that depends on which kind it is.
 *
 * <p>Exists because there are two vocabularies and only one of them is an enum. {@link DocKind}
 * names the three kinds with code attached to them; {@code document_types} (V87) holds the rest,
 * grows at runtime, and therefore can never be enumerated in Java — that is the entire point of
 * making it a table. Every layer below this class used to be typed on {@link DocKind}, so a
 * perfectly valid document type could be saved as a path (the form went through
 * {@code saveLocation(paysId, String code, …)}) and then not be selectable in the panel that
 * shows whether it resolves: {@code parseKind} returned empty for it and four endpoints answered
 * a bodiless 400. The form spoke codes, the table spoke enum.
 *
 * <p>Year scoping is the only per-kind behaviour, and for a document type the answer is always
 * false — a per-year document folder would have to exist before January's first upload and
 * nothing creates it, which is why {@link SharePointLocationService#validateTemplateForCode}
 * refuses {@code {year}} outside the enum. So carrying that one flag alongside the code is
 * enough for the store, the resolver and the admin service to stop caring which vocabulary a
 * kind came from.
 */
public record KindRef(String code, boolean yearScoped) {

    /**
     * What a code may look like. Not a security boundary — every use is a bound parameter — but
     * it keeps junk out of {@code employee_sharepoint_folders.doc_kind}, a column whose values
     * are matched exactly and which no constraint protects.
     */
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    /** The built-in kind, with its own year-scoping rule. */
    public static KindRef of(DocKind kind) {
        return new KindRef(kind.name(), kind.isYearScoped());
    }

    /**
     * Parses a code from a query string against BOTH vocabularies.
     *
     * <p>A code that matches no {@link DocKind} is taken as a document type rather than
     * rejected. Whether that type actually exists is deliberately NOT checked here: these
     * callers are country-agnostic (the status table spans every entity at once) while
     * {@code document_types} is per country, so the only honest answer at this level is "this is
     * a well-formed kind" — and an unconfigured one then resolves to NO_CONFIG per employee,
     * which is exactly what the panel is for. The write path
     * ({@code SharePointAdminService.saveLocation}) does validate against the country's type
     * list, so a typo still cannot be stored as a path.
     *
     * @return empty only for null, blank, or a malformed code
     */
    public static Optional<KindRef> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) return Optional.empty();
        return Optional.of(DocKind.from(code).map(KindRef::of)
                .orElseGet(() -> new KindRef(code, false)));
    }

    /** True when this is one of the three built-in kinds. */
    public boolean isBuiltIn() {
        return DocKind.from(code).isPresent();
    }

    /** The enum constant when this is a built-in kind, else empty. */
    public Optional<DocKind> asDocKind() {
        return DocKind.from(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
