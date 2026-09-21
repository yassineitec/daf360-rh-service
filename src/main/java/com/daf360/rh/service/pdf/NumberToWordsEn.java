package com.daf360.rh.service.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** English counterpart to {@link NumberToWordsFr} — same bound (up to 999,999). */
public final class NumberToWordsEn {

    private NumberToWordsEn() {}

    private static final String[] UNITS = {
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    };

    private static final String[] TENS = {
        "", "ten", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety"
    };

    public static String convert(BigDecimal amount) {
        long whole = amount.setScale(0, RoundingMode.FLOOR).longValue();
        if (whole == 0) return "zero";
        return convertLong(whole).trim();
    }

    private static String convertLong(long n) {
        if (n < 0)          return "minus " + convertLong(-n);
        if (n < 20)         return UNITS[(int) n];
        if (n < 100)        return convertTens(n);
        if (n < 1_000)      return convertHundreds(n);
        if (n < 1_000_000)  return convertThousands(n);
        return String.valueOf(n);
    }

    private static String convertTens(long n) {
        int t = (int) (n / 10);
        int u = (int) (n % 10);
        if (u == 0) return TENS[t];
        return TENS[t] + "-" + UNITS[u];
    }

    private static String convertHundreds(long n) {
        long h    = n / 100;
        long rest = n % 100;
        String p  = UNITS[(int) h] + " hundred";
        if (rest == 0) return p;
        return p + " and " + convertLong(rest);
    }

    private static String convertThousands(long n) {
        long th   = n / 1_000;
        long rest = n % 1_000;
        String p  = convertLong(th) + " thousand";
        if (rest == 0) return p;
        return p + " " + convertLong(rest);
    }
}
