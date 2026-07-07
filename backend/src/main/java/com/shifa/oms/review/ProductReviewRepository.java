package com.shifa.oms.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link ProductReview}.
 *
 * <p>Public reads only ever ask for {@link ReviewStatus#APPROVED} rows; the
 * moderation queue filters by status; and the aggregate finders compute the
 * average rating + count over APPROVED reviews only (both per-product and in a
 * single batch query for the catalog listing).
 */
public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    /** Reviews for a product in a given moderation state, newest first (public read uses APPROVED). */
    List<ProductReview> findByProductIdAndStatusOrderByCreatedAtDesc(Long productId, ReviewStatus status);

    /** All reviews in a moderation state, newest first (admin queue). */
    List<ProductReview> findByStatusOrderByCreatedAtDesc(ReviewStatus status);

    /** All reviews, newest first (admin queue with no status filter). */
    List<ProductReview> findAllByOrderByCreatedAtDesc();

    /** A customer's existing review for a product, if any (duplicate handling → update). */
    Optional<ProductReview> findByUserIdAndProductId(Long userId, Long productId);

    /**
     * Aggregate rating (average + count) over APPROVED reviews for one product.
     * Returns a single row even when there are no reviews (count 0, null average).
     */
    @Query("""
            SELECT AVG(r.rating) AS average, COUNT(r) AS count
            FROM ProductReview r
            WHERE r.productId = :productId AND r.status = com.shifa.oms.review.ReviewStatus.APPROVED
            """)
    RatingAggregate aggregateForProduct(@Param("productId") Long productId);

    /**
     * Aggregate rating grouped by product over APPROVED reviews, for a batch of
     * products (the catalog listing). Products with no approved reviews are
     * simply absent from the result.
     */
    @Query("""
            SELECT r.productId AS productId, AVG(r.rating) AS average, COUNT(r) AS count
            FROM ProductReview r
            WHERE r.status = com.shifa.oms.review.ReviewStatus.APPROVED
              AND r.productId IN :productIds
            GROUP BY r.productId
            """)
    List<ProductRatingAggregate> aggregateByProductIds(@Param("productIds") Collection<Long> productIds);

    /** Projection for a single-product rating aggregate. */
    interface RatingAggregate {
        Double getAverage();

        long getCount();
    }

    /** Projection for a per-product rating aggregate row (batch listing). */
    interface ProductRatingAggregate {
        Long getProductId();

        Double getAverage();

        long getCount();
    }
}
