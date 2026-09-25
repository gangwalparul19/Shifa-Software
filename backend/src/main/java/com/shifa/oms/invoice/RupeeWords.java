package com.shifa.oms.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spells an amount in Indian-English words for the invoice "amount in words"
 * line, e.g. {@code 1154.99 → "ONE THOUSAND ONE HUNDRED FIFTY FOUR RUPEES NINETY
 * NINE PAISE ONLY"}. Pure and side-effect free.
 *
 * <p>Uses the Indian numbering system (thousand, lakh, crore). Paise are the two
 * decimal places, rounded HALF_UP. Zero rupees renders "ZERO RUPEES … ONLY".
 */
public final class RupeeWords {

    private static final String[] UNITS = {
            "", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE",
            "TEN", "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN", "FIFTEEN", "SIXTEEN",
            "SEVENTEEN", "EIGHTEEN", "NINETEEN"
    };
    private static final String[] TENS = {
            "", "", "TWENTY", "THIRTY", "FORTY", "FIFTY", "SIXTY", "SEVENTY", "EIGHTY", "NINETY"
    };

    private RupeeWords() {
    }

    /** Spells the amount as "&lt;rupees&gt; RUPEES [&lt;paise&gt; PAISE] ONLY". */
    public static String toWords(BigDecimal amount) {
        BigDecimal safe = (amount != null ? amount : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        long rupees = safe.longValue();
        int paise = safe.subtract(new BigDecimal(rupees)).movePointRight(2).intValue();

        StringBuilder sb = new StringBuilder();
        sb.append(rupees == 0 ? "ZERO" : indianWords(rupees));
        sb.append(rupees == 1 ? " RUPEE" : " RUPEES");
        if (paise > 0) {
            // Matches the invoice sample: "… RUPEES NINETY NINE PAISE ONLY" (no "AND").
            sb.append(' ').append(twoDigitWords(paise)).append(" PAISE");
        }
        sb.append(" ONLY");
        return sb.toString();
    }

    /** Positive long → Indian-system words (crore/lakh/thousand/hundred). */
    private static String indianWords(long n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        long crore = n / 10000000;
        n %= 10000000;
        long lakh = n / 100000;
        n %= 100000;
        long thousand = n / 1000;
        n %= 1000;
        long hundred = n / 100;
        long rest = n % 100;

        if (crore > 0) {
            sb.append(indianWords(crore)).append(" CRORE ");
        }
        if (lakh > 0) {
            sb.append(twoDigitWords((int) lakh)).append(" LAKH ");
        }
        if (thousand > 0) {
            sb.append(twoDigitWords((int) thousand)).append(" THOUSAND ");
        }
        if (hundred > 0) {
            sb.append(UNITS[(int) hundred]).append(" HUNDRED ");
        }
        if (rest > 0) {
            sb.append(twoDigitWords((int) rest)).append(' ');
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** 0..99 → words. */
    private static String twoDigitWords(int n) {
        if (n < 20) {
            return UNITS[n];
        }
        int t = n / 10;
        int u = n % 10;
        return u == 0 ? TENS[t] : TENS[t] + " " + UNITS[u];
    }
}
