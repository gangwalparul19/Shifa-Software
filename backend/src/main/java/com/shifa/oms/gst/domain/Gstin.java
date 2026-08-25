package com.shifa.oms.gst.domain;

import java.util.regex.Pattern;

/**
 * Standard 15-character GSTIN format gate (GST filing compliance, Req 1.2).
 *
 * <p>A GSTIN is composed of:
 * <ul>
 *   <li>2-digit state code,</li>
 *   <li>10-character PAN (5 letters + 4 digits + 1 letter),</li>
 *   <li>1 entity digit,</li>
 *   <li>the fixed letter {@code Z},</li>
 *   <li>1 checksum character (digit or letter).</li>
 * </ul>
 *
 * <p>This validates the <strong>format only</strong> — it does not verify the checksum
 * character (out of scope; the GST portal re-validates on import).
 */
public final class Gstin {

    /** Canonical 15-char GSTIN pattern (format only, no checksum verification). */
    private static final Pattern PATTERN =
            Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]");

    private Gstin() {
    }

    /**
     * @return {@code true} when {@code gstin} matches the canonical 15-char GSTIN format;
     *         {@code false} when it is null, blank, or malformed.
     */
    public static boolean isValid(String gstin) {
        if (gstin == null) {
            return false;
        }
        String trimmed = gstin.trim();
        return !trimmed.isEmpty() && PATTERN.matcher(trimmed).matches();
    }

    /**
     * @return the first 2 characters (state code) of a valid GSTIN, or {@code null}
     *         when the input is not a valid GSTIN.
     */
    public static String stateCode(String gstin) {
        if (!isValid(gstin)) {
            return null;
        }
        return gstin.trim().substring(0, 2);
    }
}
