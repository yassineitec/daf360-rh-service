package com.daf360.rh.common;

import java.util.Locale;

/**
 * Puts a full name in the "Prénom NOM" form used for Users.fullName and the SharePoint
 * employee folders: the last word in capitals, every other word with a leading capital.
 *
 * <pre>
 *   "jean ben dupont"   → "Jean Ben DUPONT"
 *   "jean dupont"       → "Jean DUPONT"
 *   "jean-marc ben-salah" → "Jean-Marc BEN-SALAH"
 * </pre>
 *
 * With a single field there is no way to tell where the first name stops, so only the last
 * word is treated as the surname. A single word is a first name (leading capital only).
 *
 * The frontend applies the same rule in {@code shared/utils/person-name.utils.ts}; keep the
 * two in step.
 */
public final class PersonNameFormatter {

    private static final Locale FR = Locale.FRENCH;

    private PersonNameFormatter() {}

    /** The formatted name, or null when the input is null or blank. */
    public static String format(String raw) {
        if (raw == null) return null;
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) return null;

        String[] words = collapsed.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            boolean surname = words.length > 1 && i == words.length - 1;
            out.append(surname ? words[i].toUpperCase(FR) : capitalize(words[i]));
        }
        return out.toString();
    }

    /**
     * "Prénom NOM" from separate fields: every first-name word with a leading capital, the
     * whole last name in capitals ("jean", "ben dupont" → "Jean BEN DUPONT"). Either part may
     * be blank; null when both are.
     */
    public static String compose(String firstName, String lastName) {
        String first = formatFirstName(firstName);
        String last  = formatLastName(lastName);
        if (first == null) return last;
        return last == null ? first : first + " " + last;
    }

    /** Prénom field: every word with a leading capital ("jean-marc" → "Jean-Marc"); null if blank. */
    public static String formatFirstName(String firstName) {
        String first = collapse(firstName);
        if (first == null) return null;
        String[] words = first.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(capitalize(words[i]));
        }
        return out.toString();
    }

    /** Nom field: the whole last name in capitals ("ben dupont" → "BEN DUPONT"); null if blank. */
    public static String formatLastName(String lastName) {
        String last = collapse(lastName);
        return last == null ? null : last.toUpperCase(FR);
    }

    private static String collapse(String s) {
        if (s == null) return null;
        String c = s.trim().replaceAll("\\s+", " ");
        return c.isEmpty() ? null : c;
    }

    /** Lower-cases the word, then capitalises its first letter and any letter after - or '. */
    private static String capitalize(String word) {
        String lower = word.toLowerCase(FR);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean upNext = true;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            sb.append(upNext ? ch.toUpperCase(FR) : ch);
            upNext = cp == '-' || cp == '\'' || cp == '’';
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
