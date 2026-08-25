package com.shifa.oms.gst.domain;

import java.util.Set;

/**
 * GST-standard Unit Quantity Code (UQC) resolver for the GSTR-1 HSN summary (Req 3.2).
 *
 * <p>Every product line in the Table-12 HSN summary must carry a UQC drawn from the GST-standard
 * set. Products may have no UQC assigned (the {@code products.uqc} column is nullable and the
 * default is applied in code, not in the schema), so {@link #resolve(String)} maps a blank or
 * unrecognised code to the GST-standard default {@code NOS} ("numbers"), while a recognised code
 * passes through unchanged (case-insensitively normalised to its canonical upper-case form).
 *
 * <p>Pure and Spring-free so it is fully unit- and property-testable.
 */
public final class Uqc {

    /** GST-standard default unit ("numbers"), used when a product has no recognised UQC. */
    public static final String DEFAULT = "NOS";

    /**
     * The recognised subset of the GST-standard UQC codes relevant to this catalogue
     * (units, weights, volumes, and common pack forms). Codes are stored in canonical upper case.
     */
    public static final Set<String> STANDARD = Set.of(
            "NOS", // numbers
            "PCS", // pieces
            "UNT", // units
            "KGS", // kilograms
            "GMS", // grams
            "MGS", // milligrams
            "TON", // tonnes
            "QTL", // quintal
            "LTR", // litres
            "MLT", // millilitres
            "KLR", // kilolitre
            "BOX", // box
            "BTL", // bottles
            "JAR", // jar
            "BAG", // bags
            "PAC", // packs
            "CAN", // cans
            "DOZ", // dozen
            "SET", // sets
            "TUB", // tubes
            "PRS", // pairs
            "SqM", // square metres (canonical mixed-case GST code)
            "CBM"  // cubic metres
    );

    private Uqc() {
    }

    /**
     * Resolve a product's UQC to a non-blank GST-standard code (Req 3.2).
     *
     * <ul>
     *   <li>{@code null}, blank, or unrecognised → {@link #DEFAULT} ({@code NOS}).</li>
     *   <li>a recognised code → the canonical code from {@link #STANDARD}
     *       (case-insensitive match, e.g. {@code "pcs"} → {@code "PCS"}).</li>
     * </ul>
     *
     * @param productUqc the UQC stored against the product (may be null/blank)
     * @return a non-blank, recognised, canonical UQC code
     */
    public static String resolve(String productUqc) {
        if (productUqc == null) {
            return DEFAULT;
        }
        String trimmed = productUqc.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT;
        }
        for (String code : STANDARD) {
            if (code.equalsIgnoreCase(trimmed)) {
                return code;
            }
        }
        return DEFAULT;
    }
}
