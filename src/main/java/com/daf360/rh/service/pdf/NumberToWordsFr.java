package com.daf360.rh.service.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberToWordsFr {

    private NumberToWordsFr() {}

    private static final String[] UNITS = {
        "", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
        "dix-sept", "dix-huit", "dix-neuf"
    };

    private static final String[] TENS = {
        "", "dix", "vingt", "trente", "quarante", "cinquante",
        "soixante", "soixante", "quatre-vingt", "quatre-vingt"
    };

    public static String convert(BigDecimal amount) {
        long whole = amount.setScale(0, RoundingMode.FLOOR).longValue();
        if (whole == 0) return "zero";
        return convertLong(whole).trim();
    }

    private static String convertLong(long n) {
        if (n < 0)          return "moins " + convertLong(-n);
        if (n < 20)         return UNITS[(int) n];
        if (n < 100)        return convertTens(n);
        if (n < 1_000)      return convertHundreds(n);
        if (n < 1_000_000)  return convertThousands(n);
        return String.valueOf(n);
    }

    private static String convertTens(long n) {
        int t = (int) (n / 10);
        int u = (int) (n % 10);
        if (t == 7) return TENS[6] + (u == 1 ? " et onze" : (u == 0 ? "-dix" : "-" + UNITS[10 + u]));
        if (t == 8) return u == 0 ? "quatre-vingts" : "quatre-vingt-" + UNITS[u];
        if (t == 9) return "quatre-vingt-" + UNITS[10 + u];
        if (u == 0) return TENS[t];
        if (u == 1 && t < 8) return TENS[t] + " et un";
        return TENS[t] + "-" + UNITS[u];
    }

    private static String convertHundreds(long n) {
        long h    = n / 100;
        long rest = n % 100;
        String p  = h == 1 ? "cent" : (convertLong(h) + " cent");
        if (rest == 0) return h == 1 ? "cent" : p + "s";
        return p + " " + convertLong(rest);
    }

    private static String convertThousands(long n) {
        long th   = n / 1_000;
        long rest = n % 1_000;
        String p  = th == 1 ? "mille" : (convertLong(th) + " mille");
        if (rest == 0) return p;
        return p + " " + convertLong(rest);
    }
}
