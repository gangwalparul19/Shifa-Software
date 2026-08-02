package com.shifa.oms.integration.shopify;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Matches a Shopify line-item SKU to exactly one Shifa product (Req 3.3, 3.4).
 *
 * <p><b>Exactly one</b> is the whole point. If a SKU matches two products, linking to either
 * would silently attribute revenue and deduct stock from the wrong one, and the mistake would
 * be invisible afterwards. So an ambiguous match is treated the same as no match: the line is
 * recorded by name and amount, and the order goes to the review queue for a human to resolve.
 *
 * <p>Comparison trims and ignores case, because Shopify SKUs are typed by hand and
 * {@code " shr-ash-60 "} and {@code "SHR-ASH-60"} are the same product to everyone except a
 * string comparison.
 *
 * <p>Pure: the catalogue is passed in, never queried here.
 */
public final class ShopifySkuMatcher {

    private ShopifySkuMatcher() {
        // Pure static helper.
    }

    /** The outcome of matching one SKU. */
    public enum Outcome {
        /** Exactly one product matched. */
        MATCHED,
        /** The SKU was absent or blank. */
        ABSENT,
        /** No product carries this SKU. */
        NOT_FOUND,
        /** Two or more products carry this SKU, so linking either would be a guess. */
        AMBIGUOUS
    }

    /**
     * @param outcome   why the match succeeded or failed
     * @param productId the matched product, present only when {@link Outcome#MATCHED}
     */
    public record Match(Outcome outcome, Long productId) {

        public boolean isMatched() {
            return outcome == Outcome.MATCHED && productId != null;
        }

        /** Whether this line should push the order into the review queue. */
        public boolean needsReview() {
            return !isMatched();
        }
    }

    /**
     * Builds a lookup from a catalogue of {@code (productId, sku)} pairs.
     *
     * <p>A SKU appearing more than once maps to a null id, which is how ambiguity is
     * remembered rather than being resolved arbitrarily by "last one wins".
     */
    public static Map<String, Long> index(List<CatalogueEntry> catalogue) {
        Map<String, Long> bySku = new HashMap<>();
        if (catalogue == null) {
            return bySku;
        }
        for (CatalogueEntry entry : catalogue) {
            String key = normalize(entry.sku());
            if (key.isEmpty() || entry.productId() == null) {
                continue;
            }
            // A second product with the same SKU poisons the entry to a null VALUE, which
            // is how ambiguity is remembered: the key stays present so lookups can tell
            // "two products claim this SKU" apart from "no product does".
            //
            // Deliberately NOT Map.merge: its remapping function returning null REMOVES
            // the key, which would silently downgrade an ambiguous SKU to NOT_FOUND.
            if (bySku.containsKey(key)) {
                bySku.put(key, null);
            } else {
                bySku.put(key, entry.productId());
            }
        }
        return bySku;
    }

    /** One catalogue row for the index. */
    public record CatalogueEntry(Long productId, String sku) {
    }

    /**
     * Matches one SKU against an index built by {@link #index(List)}.
     */
    public static Match match(String sku, Map<String, Long> indexBySku) {
        String key = normalize(sku);
        if (key.isEmpty()) {
            return new Match(Outcome.ABSENT, null);
        }
        if (indexBySku == null || !indexBySku.containsKey(key)) {
            return new Match(Outcome.NOT_FOUND, null);
        }
        Long productId = indexBySku.get(key);
        return productId == null
                ? new Match(Outcome.AMBIGUOUS, null)
                : new Match(Outcome.MATCHED, productId);
    }

    /** Convenience: the matched product id, if any. */
    public static Optional<Long> matchedProductId(String sku, Map<String, Long> indexBySku) {
        Match match = match(sku, indexBySku);
        return match.isMatched() ? Optional.of(match.productId()) : Optional.empty();
    }

    private static String normalize(String sku) {
        return sku == null ? "" : sku.trim().toLowerCase(Locale.ROOT);
    }
}
