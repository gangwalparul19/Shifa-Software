package com.shifa.oms.integration.shopify;

import java.util.Optional;

/**
 * Normalises a Shopify contact number to the 10-digit form Shifa stores (Req 3.2).
 *
 * <p>{@code orders.customer_mobile} is {@code VARCHAR(10) NOT NULL}, so anything that is
 * not exactly ten digits cannot be stored — which is why this returns an
 * {@link Optional} rather than a best effort. A number that does not normalise cleanly
 * becomes a {@code MISSING_CONTACT} review reason instead of a truncated, undialable value.
 *
 * <p>Shopify stores whatever the buyer typed, so the input realistically arrives as
 * {@code "+91 98123 45678"}, {@code "098123-45678"}, {@code "0091-9812345678"} or
 * {@code "9812345678"}.
 *
 * <p>Pure and total: no Spring, no I/O, never throws.
 */
public final class MobileNumberNormalizer {

    /** India's country calling code, the only one this business ships to. */
    private static final String COUNTRY_CODE = "91";

    private static final int NATIONAL_LENGTH = 10;

    private MobileNumberNormalizer() {
        // Pure static helper.
    }

    /**
     * @param raw the contact number as Shopify supplied it
     * @return the 10-digit national number, or empty when the input cannot yield one
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        // Strip everything that is not a digit: spaces, +, -, (), and any stray letters.
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String value = digits.toString();

        // Peel an international or trunk prefix, but only when doing so leaves exactly a
        // national number — never blindly, or a genuine number starting with 91 would be
        // mangled.
        if (value.length() == NATIONAL_LENGTH + COUNTRY_CODE.length() && value.startsWith(COUNTRY_CODE)) {
            value = value.substring(COUNTRY_CODE.length());
        } else if (value.length() == NATIONAL_LENGTH + 1 && value.startsWith("0")) {
            value = value.substring(1);
        } else if (value.length() == NATIONAL_LENGTH + 2 + COUNTRY_CODE.length()
                && value.startsWith("00" + COUNTRY_CODE)) {
            // "0091…" — the ISO international prefix followed by the country code.
            value = value.substring(2 + COUNTRY_CODE.length());
        }

        return value.length() == NATIONAL_LENGTH ? Optional.of(value) : Optional.empty();
    }

    /**
     * The value to store, given {@code orders.customer_mobile} is NOT NULL: the normalised
     * number, or the empty string when there is none (Req 3.5).
     */
    public static String normalizeOrEmpty(String raw) {
        return normalize(raw).orElse("");
    }
}
