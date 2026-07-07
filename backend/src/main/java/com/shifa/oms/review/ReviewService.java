package com.shifa.oms.review;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRatingLookup;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.review.dto.AdminReviewResponse;
import com.shifa.oms.review.dto.ProductReviewsResponse;
import com.shifa.oms.review.dto.ReviewRequest;
import com.shifa.oms.review.dto.ReviewSubmissionResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product reviews & ratings application service (Phase C).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><strong>Submit</strong> — an authenticated customer submits a review for
 *       a published product. The review is created {@link ReviewStatus#PENDING}
 *       (awaits moderation), the author name is snapshotted from the customer's
 *       profile, and a {@code verified} flag is computed from order history (did
 *       they purchase this product). A non-purchaser is <em>not</em> blocked —
 *       they are simply not flagged as verified. A customer's second submission
 *       for the same product updates their existing review (back to PENDING)
 *       rather than creating a duplicate.</li>
 *   <li><strong>Public read</strong> — only APPROVED reviews are exposed, with an
 *       aggregate (average + count) and a per-star breakdown.</li>
 *   <li><strong>Moderation</strong> — approve/reject transitions stamping the
 *       moderator + time.</li>
 *   <li><strong>Rating lookup</strong> — implements {@link ProductRatingLookup}
 *       so the catalog/detail responses surface average + count (APPROVED only)
 *       without an extra call.</li>
 * </ul>
 */
@Service
public class ReviewService implements ProductRatingLookup {

    private final ProductReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public ReviewService(ProductReviewRepository reviewRepository,
                         ProductRepository productRepository,
                         OrderRepository orderRepository,
                         UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    // --- Submit -------------------------------------------------------------

    /**
     * Submits (or re-submits) a review for the calling customer. The product must
     * be published (else 404). The review is flagged verified when the customer
     * has previously purchased the product, and always enters the moderation
     * queue as PENDING.
     */
    @Transactional
    public ReviewSubmissionResponse submit(Long userId, ReviewRequest request) {
        Product product = productRepository
                .findByIdAndVisibility(request.productId(), ProductVisibility.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + request.productId() + " is not available."));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " does not exist."));

        boolean verified = orderRepository.countCustomerPurchasesOfProduct(
                userId, user.getMobile(), product.getId()) > 0;

        String title = blankToNull(request.title());
        String body = blankToNull(request.body());
        int rating = request.rating();

        // Duplicate handling: one review per customer per product. A re-submission
        // updates the existing review and returns it to PENDING for re-moderation.
        ProductReview review = reviewRepository.findByUserIdAndProductId(userId, product.getId())
                .map(existing -> {
                    existing.resubmit(rating, title, body, verified);
                    return existing;
                })
                .orElseGet(() -> new ProductReview(
                        product.getId(), userId, authorName(user), rating, title, body, verified));

        return ReviewSubmissionResponse.from(reviewRepository.save(review));
    }

    // --- Public read --------------------------------------------------------

    /** APPROVED reviews for a product with aggregate + per-star breakdown (public). */
    @Transactional(readOnly = true)
    public ProductReviewsResponse publicReviews(Long productId) {
        List<ProductReview> approved = reviewRepository
                .findByProductIdAndStatusOrderByCreatedAtDesc(productId, ReviewStatus.APPROVED);
        return ProductReviewsResponse.of(productId, approved);
    }

    // --- Moderation ---------------------------------------------------------

    /** The moderation queue, optionally filtered by status (null → all), newest first. */
    @Transactional(readOnly = true)
    public List<AdminReviewResponse> moderationQueue(@Nullable ReviewStatus status) {
        List<ProductReview> reviews = status == null
                ? reviewRepository.findAllByOrderByCreatedAtDesc()
                : reviewRepository.findByStatusOrderByCreatedAtDesc(status);
        return toAdminResponses(reviews);
    }

    /** Approves a pending/rejected review → APPROVED, stamping the moderator. */
    @Transactional
    public AdminReviewResponse approve(Long reviewId, Long moderatorUserId) {
        ProductReview review = requireReview(reviewId);
        review.approve(moderatorUserId, LocalDateTime.now());
        ProductReview saved = reviewRepository.save(review);
        return AdminReviewResponse.from(saved, productName(saved.getProductId()));
    }

    /** Rejects a review → REJECTED, stamping the moderator. */
    @Transactional
    public AdminReviewResponse reject(Long reviewId, Long moderatorUserId) {
        ProductReview review = requireReview(reviewId);
        review.reject(moderatorUserId, LocalDateTime.now());
        ProductReview saved = reviewRepository.save(review);
        return AdminReviewResponse.from(saved, productName(saved.getProductId()));
    }

    // --- ProductRatingLookup (catalog/detail enrichment) --------------------

    @Override
    @Transactional(readOnly = true)
    public ProductRating ratingFor(Long productId) {
        ProductReviewRepository.RatingAggregate aggregate =
                reviewRepository.aggregateForProduct(productId);
        if (aggregate == null || aggregate.getCount() == 0) {
            return ProductRating.NONE;
        }
        return new ProductRating(round(aggregate.getAverage()), aggregate.getCount());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ProductRating> ratingsFor(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductRating> result = new HashMap<>();
        for (ProductReviewRepository.ProductRatingAggregate row
                : reviewRepository.aggregateByProductIds(productIds)) {
            if (row.getCount() > 0) {
                result.put(row.getProductId(), new ProductRating(round(row.getAverage()), row.getCount()));
            }
        }
        return result;
    }

    // --- Helpers ------------------------------------------------------------

    private List<AdminReviewResponse> toAdminResponses(List<ProductReview> reviews) {
        if (reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = productNames(
                reviews.stream().map(ProductReview::getProductId).toList());
        return reviews.stream()
                .map(review -> AdminReviewResponse.from(
                        review, names.get(review.getProductId())))
                .toList();
    }

    private Map<Long, String> productNames(Collection<Long> productIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            names.put(product.getId(), product.getName());
        }
        return names;
    }

    private String productName(Long productId) {
        return productRepository.findById(productId).map(Product::getName).orElse(null);
    }

    private ProductReview requireReview(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Review " + reviewId + " does not exist."));
    }

    private static String authorName(User user) {
        String name = user.getFullName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return user.getUsername();
    }

    private static Double round(Double average) {
        if (average == null) {
            return null;
        }
        return BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
