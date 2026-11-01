package com.shifa.oms.incentive.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display formatting for incentive text (Indian digit grouping). */
public final class IncentiveFormat {

    private static final String RUPEE = "\u20B9";

    private IncentiveFormat() {
    }

    /** "₹1,23,456" for whole rupees, "₹1,234.50" when there are paise. */
    public static String inr(BigDecimal amount) {
        BigDecimal v = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String paise = plain.substring(dot + 1);
        return (negative ? "-" : "") + RUPEE + group(whole) + ("00".equals(paise) ? "" : "." + paise);
    }

    /** Indian grouping: the last three digits, then groups of two ("12,34,567"). */
    private static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String head = digits.substring(0, digits.length() - 3);
        String tail = digits.substring(digits.length() - 3);
        StringBuilder out = new StringBuilder();
        int first = head.length() % 2;
        if (first > 0) {
            out.append(head, 0, first);
        }
        for (int i = first; i < head.length(); i += 2) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(head, i, i + 2);
        }
        return out + "," + tail;
    }
}
