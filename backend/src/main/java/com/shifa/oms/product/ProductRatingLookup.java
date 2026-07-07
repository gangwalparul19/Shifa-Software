package com.shifa.oms.product;

import java.util.Collection;
import java.util.Map;

/**
 * Read-only lookup of a product's public rating aggregate (average + count over
 * APPROVED reviews). Declared in the product package so {@link ProductService}
 * can enrich catalog/detail responses without depending on the review module;
 * the review module supplies the implementation. Kept optional (may be absent)
 * so the product module stays usable in isolation.
 */
public interface ProductRatingLookup {

    /** The rating aggregate for a single product (zero when it has no approved reviews). */
    ProductRating ratingFor(Long productId);

    /**
     * Rating aggregates for a batch of products, keyed by product id. Products
     * with no approved reviews may be absent from the map (treated as zero).
     */
    Map<Long, ProductRating> ratingsFor(Collection<Long> productIds);

    /**
     * A product's public rating aggregate. {@code average} is {@code null} when
     * there are no approved reviews ({@code count == 0}) so the storefront can
     * hide the stars; otherwise it is the mean of the approved ratings.
     */
    record ProductRating(Double average, long count) {

        /** The empty aggregate: no approved reviews. */
        public static final ProductRating NONE = new ProductRating(null, 0L);
    }
}
