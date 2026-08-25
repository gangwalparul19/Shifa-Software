package com.shifa.oms.gst.domain;

import java.math.BigDecimal;

/**
 * HSN-length compliance rules for the GSTR-1 Table-12 HSN summary
 * (GST Filing Compliance, Req 3.3 &amp; 3.4).
 *
 * <p>GST reporting requires a minimum HSN code length that depends on the seller's aggregate
 * turnover: a seller whose aggregate turnover exceeds ₹5 crore must report at least 6-digit HSN
 * codes; otherwise a 4-digit minimum applies. {@link #minLength(BigDecimal)} resolves that minimum
 * and {@link #isCompliant(String, int)} tests whether a given HSN code meets it, so the builder can
 * flag any product whose HSN is shorter than the enforced minimum (Req 3.4).
 *
 * <p>The turnover value is a nullable seller config ({@code app_settings.aggregate_turnover}); when
 * it is unset the 4-digit rule applies. HSN "length" is measured by the number of digit characters,
 * so stray whitespace or separators do not affect the result.
 *
 * <p>Pure and Spring-free so it is fully unit- and property-testable.
 */
public final class HsnCompliance {

    /**
     * The aggregate-turnover level (₹5 crore) above which 6-digit HSN reporting is mandatory
     * (Req 3.3). A turnover strictly greater than this requires 6 digits; at or below it (or unset)
     * the 4-digit minimum applies.
     */
    public static final BigDecimal SIX_DIGIT_TURNOVER = new BigDecimal("50000000");

    private HsnCompliance() {
    }

    /**
     * Resolve the minimum HSN length enforced for the seller's aggregate turnover (Req 3.3).
     *
     * @param aggregateTurnover the seller's configured aggregate turnover; {@code null} defaults to
     *                          the 4-digit rule
     * @return {@code 6} when {@code aggregateTurnover} is strictly greater than ₹5 crore, else {@code 4}
     */
    public static int minLength(BigDecimal aggregateTurnover) {
        if (aggregateTurnover != null && aggregateTurnover.compareTo(SIX_DIGIT_TURNOVER) > 0) {
            return 6;
        }
        return 4;
    }

    /**
     * Test whether an HSN code meets the enforced minimum length (Req 3.4).
     *
     * <p>Compliance is measured by digit count, so a {@code null}, blank, or too-short HSN is
     * non-compliant, while any HSN with at least {@code minLength} digits is compliant.
     *
     * @param hsn       the product's HSN code (may be null/blank)
     * @param minLength the enforced minimum HSN length (typically {@code 4} or {@code 6})
     * @return {@code true} when the HSN has at least {@code minLength} digit characters, else {@code false}
     */
    public static boolean isCompliant(String hsn, int minLength) {
        if (hsn == null) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < hsn.length(); i++) {
            if (Character.isDigit(hsn.charAt(i))) {
                digits++;
            }
        }
        return digits >= minLength;
    }
}
