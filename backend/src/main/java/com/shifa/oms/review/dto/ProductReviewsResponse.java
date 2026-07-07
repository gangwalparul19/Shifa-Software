package com.shifa.oms.review.dto;

import com.shifa.oms.review.ProductReview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The public reviews payload for a product ({@code GET /api/catalog/products/{id}/reviews}):
 * the list of APPROVED reviews plus an aggregate (average rating rounded to one
 * decimal, total count) and a per-star breakdown (how many reviews gave 1..5
 * stars) for a ratings histogram on the storefront.
 *
 * <p>Average is {@code null} and the breakdown is all-zero when there are no
 * approved reviews.
 */
public record ProductReviewsResponse(
        Long productId,
        Double averageRating,
        long reviewCount,
        Map<Integer, Long> breakdown,
        List<ReviewResponse> reviews) {

    /**
     * Builds the payload from the already-filtered list of APPROVED reviews for
     * a product (newest first). Pure aggregation so it is trivially testable.
     */
    public static ProductReviewsResponse of(Long productId, List<ProductReview> approved) {
        Map<Integer, Long> breakdown = new TreeMap<>();
        for (int star = 1; star <= 5; star++) {
            breakdown.put(star, 0L);
        }
        long sum = 0;
        for (ProductReview review : approved) {
            int star = review.getRating();
            breakdown.merge(star, 1L, Long::sum);
            sum += star;
        }
        long count = approved.size();
        Double average = count == 0
                ? null
                : BigDecimal.valueOf(sum)
                        .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP)
                        .doubleValue();
        List<ReviewResponse> reviews = approved.stream().map(ReviewResponse::from).toList();
        return new ProductReviewsResponse(productId, average, count, breakdown, reviews);
    }
}
