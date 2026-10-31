package com.shifa.oms.order;

import java.util.Locale;

/** Canonical representation used for payment capture and settlement matching. */
public final class UtrNormalizer {

    private UtrNormalizer() {
    }

    /**
     * Trims, removes Java whitespace, and upper-cases a provider reference.
     * Blank values are represented as {@code null} so database uniqueness can
     * ignore payments that do not carry a receiving-account reference.
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
