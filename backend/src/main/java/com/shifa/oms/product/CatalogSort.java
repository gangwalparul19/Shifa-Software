package com.shifa.oms.product;

import java.util.Locale;

/**
 * Server-side sort options for the public catalog listing.
 *
 * <ul>
 *   <li>{@link #RELEVANCE} — default; preserves the catalog's natural order
 *       (name ascending, matching the legacy behaviour).</li>
 *   <li>{@link #PRICE_ASC} / {@link #PRICE_DESC} — by sale price.</li>
 *   <li>{@link #NAME_ASC} — alphabetical by name.</li>
 *   <li>{@link #NEWEST} — most recently created first.</li>
 * </ul>
 */
public enum CatalogSort {
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC,
    NAME_ASC,
    NEWEST;

    /**
     * Parses the API {@code sort} parameter (case-insensitive, snake or hyphen),
     * defaulting to {@link #RELEVANCE} for null/blank/unknown values so a bad
     * value never breaks the listing.
     */
    public static CatalogSort fromParam(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "price_asc" -> PRICE_ASC;
            case "price_desc" -> PRICE_DESC;
            case "name_asc" -> NAME_ASC;
            case "newest" -> NEWEST;
            default -> RELEVANCE;
        };
    }
}
