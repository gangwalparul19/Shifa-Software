package com.shifa.oms.review;

import com.shifa.oms.auth.Role;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReviewService} with mocked repositories (no DB).
 *
 * <p>Covers: submit creates a PENDING review with the verified-purchase flag
 * derived from order history (and does not block non-purchasers); duplicate
 * handling updates the existing review back to PENDING; public read returns only
 * APPROVED reviews with the correct average/count; approve/reject transitions;
 * and the {@link ProductRatingLookup} aggregate (APPROVED only).
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final Long ALICE = 1L;
    private static final Long PRODUCT_ID = 42L;

    @Mock
    private ProductReviewRepository reviewRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;

    private ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(reviewRepository, productRepository, orderRepository, userRepository);
    }

    private Product publishedProduct(long id) {
        Product p = new Product("SKU-" + id, "Ashwagandha", "d",
                new BigDecimal("999.00"), new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private User customer(long id, String name, String mobile) {
        User u = new User("alice", "hash", Role.CUSTOMER, name, true);
        u.setMobile(mobile);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private ReviewRequest request(int rating, String title, String body) {
        return new ReviewRequest(PRODUCT_ID, rating, title, body);
    }

    // --- Submit: PENDING + verified from order history ----------------------

    @Test
    void submitCreatesPendingVerifiedReviewWhenCustomerPurchasedProduct() {
        when(productRepository.findByIdAndVisibility(PRODUCT_ID, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.of(publishedProduct(PRODUCT_ID)));
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer(ALICE, "Alice", "9876543210")));
        when(orderRepository.countCustomerPurchasesOfProduct(ALICE, "9876543210", PRODUCT_ID))
                .thenReturn(1L);
        when(reviewRepository.findByUserIdAndProductId(ALICE, PRODUCT_ID)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewSubmissionResponse response = service.submit(ALICE, request(5, "Great", "Loved it"));

        assertThat(response.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(response.verified()).isTrue();
        assertThat(response.rating()).isEqualTo(5);

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewRepository).save(captor.capture());
        ProductReview saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(saved.isVerified()).isTrue();
        assertThat(saved.getAuthorName()).isEqualTo("Alice");
        assertThat(saved.getUserId()).isEqualTo(ALICE);
    }

    @Test
    void submitDoesNotBlockNonPurchaserButFlagsUnverified() {
        when(productRepository.findByIdAndVisibility(PRODUCT_ID, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.of(publishedProduct(PRODUCT_ID)));
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer(ALICE, "Alice", "9876543210")));
        when(orderRepository.countCustomerPurchasesOfProduct(ALICE, "9876543210", PRODUCT_ID))
                .thenReturn(0L);
        when(reviewRepository.findByUserIdAndProductId(ALICE, PRODUCT_ID)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewSubmissionResponse response = service.submit(ALICE, request(4, null, null));

        assertThat(response.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(response.verified()).isFalse();
    }

    @Test
    void submitForUnpublishedOrMissingProductIsNotFound() {
        when(productRepository.findByIdAndVisibility(PRODUCT_ID, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(ALICE, request(3, null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not available");
    }

    // --- Duplicate handling: update existing review, back to PENDING --------

    @Test
    void submitUpdatesExistingReviewInsteadOfCreatingDuplicate() {
        when(productRepository.findByIdAndVisibility(PRODUCT_ID, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.of(publishedProduct(PRODUCT_ID)));
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer(ALICE, "Alice", "9876543210")));
        when(orderRepository.countCustomerPurchasesOfProduct(ALICE, "9876543210", PRODUCT_ID))
                .thenReturn(1L);

        ProductReview existing = new ProductReview(PRODUCT_ID, ALICE, "Alice", 2, "Meh", "old", false);
        existing.approve(99L, LocalDateTime.now()); // previously approved
        when(reviewRepository.findByUserIdAndProductId(ALICE, PRODUCT_ID)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewSubmissionResponse response = service.submit(ALICE, request(5, "Updated", "new body"));

        // The same entity is updated and returned to the moderation queue.
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.title()).isEqualTo("Updated");
        assertThat(response.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(existing.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(existing.getModeratedBy()).isNull();
        assertThat(existing.getModeratedAt()).isNull();
        verify(reviewRepository).save(existing);
    }

    // --- Public read: APPROVED only + aggregate -----------------------------

    @Test
    void publicReviewsReturnsApprovedWithAverageAndCount() {
        ProductReview five = new ProductReview(PRODUCT_ID, 1L, "Alice", 5, "A", "a", true);
        ProductReview four = new ProductReview(PRODUCT_ID, 2L, "Bob", 4, "B", "b", false);
        five.approve(9L, LocalDateTime.now());
        four.approve(9L, LocalDateTime.now());
        when(reviewRepository.findByProductIdAndStatusOrderByCreatedAtDesc(PRODUCT_ID, ReviewStatus.APPROVED))
                .thenReturn(List.of(five, four));

        ProductReviewsResponse response = service.publicReviews(PRODUCT_ID);

        assertThat(response.reviewCount()).isEqualTo(2);
        assertThat(response.averageRating()).isEqualTo(4.5);
        assertThat(response.breakdown().get(5)).isEqualTo(1L);
        assertThat(response.breakdown().get(4)).isEqualTo(1L);
        assertThat(response.breakdown().get(1)).isEqualTo(0L);
        assertThat(response.reviews()).hasSize(2);
        // Only the APPROVED-status finder is queried, so rejected/pending never leak.
        verify(reviewRepository)
                .findByProductIdAndStatusOrderByCreatedAtDesc(PRODUCT_ID, ReviewStatus.APPROVED);
    }

    @Test
    void publicReviewsForProductWithNoApprovedReviewsIsEmpty() {
        when(reviewRepository.findByProductIdAndStatusOrderByCreatedAtDesc(PRODUCT_ID, ReviewStatus.APPROVED))
                .thenReturn(List.of());

        ProductReviewsResponse response = service.publicReviews(PRODUCT_ID);

        assertThat(response.reviewCount()).isZero();
        assertThat(response.averageRating()).isNull();
    }

    // --- Moderation transitions --------------------------------------------

    @Test
    void approveTransitionsToApprovedAndStampsModerator() {
        ProductReview review = new ProductReview(PRODUCT_ID, ALICE, "Alice", 5, "t", "b", true);
        ReflectionTestUtils.setField(review, "id", 7L);
        when(reviewRepository.findById(7L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(publishedProduct(PRODUCT_ID)));

        AdminReviewResponse response = service.approve(7L, 100L);

        assertThat(response.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(response.moderatedBy()).isEqualTo(100L);
        assertThat(response.moderatedAt()).isNotNull();
        assertThat(response.productName()).isEqualTo("Ashwagandha");
    }

    @Test
    void rejectTransitionsToRejectedAndStampsModerator() {
        ProductReview review = new ProductReview(PRODUCT_ID, ALICE, "Alice", 1, "t", "b", false);
        ReflectionTestUtils.setField(review, "id", 8L);
        when(reviewRepository.findById(8L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(publishedProduct(PRODUCT_ID)));

        AdminReviewResponse response = service.reject(8L, 100L);

        assertThat(response.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(response.moderatedBy()).isEqualTo(100L);
        assertThat(response.moderatedAt()).isNotNull();
    }

    @Test
    void approveMissingReviewIsNotFound() {
        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(404L, 100L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void moderationQueueFiltersByStatus() {
        ProductReview pending = new ProductReview(PRODUCT_ID, ALICE, "Alice", 5, "t", "b", true);
        when(reviewRepository.findByStatusOrderByCreatedAtDesc(ReviewStatus.PENDING))
                .thenReturn(List.of(pending));
        when(productRepository.findAllById(any())).thenReturn(List.of(publishedProduct(PRODUCT_ID)));

        List<AdminReviewResponse> queue = service.moderationQueue(ReviewStatus.PENDING);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).productName()).isEqualTo("Ashwagandha");
        verify(reviewRepository).findByStatusOrderByCreatedAtDesc(ReviewStatus.PENDING);
    }

    @Test
    void moderationQueueWithNullStatusReturnsAll() {
        when(reviewRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        List<AdminReviewResponse> queue = service.moderationQueue(null);

        assertThat(queue).isEmpty();
        verify(reviewRepository).findAllByOrderByCreatedAtDesc();
    }

    // --- Rating lookup (APPROVED only) --------------------------------------

    @Test
    void ratingForReflectsApprovedAggregate() {
        ProductReviewRepository.RatingAggregate aggregate =
                mock(ProductReviewRepository.RatingAggregate.class);
        when(aggregate.getCount()).thenReturn(3L);
        when(aggregate.getAverage()).thenReturn(4.333);
        when(reviewRepository.aggregateForProduct(PRODUCT_ID)).thenReturn(aggregate);

        ProductRatingLookup.ProductRating rating = service.ratingFor(PRODUCT_ID);

        assertThat(rating.count()).isEqualTo(3L);
        assertThat(rating.average()).isEqualTo(4.3); // rounded to one decimal
    }

    @Test
    void ratingForWithNoApprovedReviewsIsNone() {
        ProductReviewRepository.RatingAggregate aggregate =
                mock(ProductReviewRepository.RatingAggregate.class);
        when(aggregate.getCount()).thenReturn(0L);
        when(reviewRepository.aggregateForProduct(PRODUCT_ID)).thenReturn(aggregate);

        ProductRatingLookup.ProductRating rating = service.ratingFor(PRODUCT_ID);

        assertThat(rating.count()).isZero();
        assertThat(rating.average()).isNull();
    }

    @Test
    void ratingsForBatchesAndSkipsProductsWithoutApprovedReviews() {
        ProductReviewRepository.ProductRatingAggregate row =
                mock(ProductReviewRepository.ProductRatingAggregate.class);
        when(row.getProductId()).thenReturn(PRODUCT_ID);
        when(row.getCount()).thenReturn(2L);
        when(row.getAverage()).thenReturn(5.0);
        when(reviewRepository.aggregateByProductIds(eq(List.of(PRODUCT_ID, 43L))))
                .thenReturn(List.of(row));

        Map<Long, ProductRatingLookup.ProductRating> ratings =
                service.ratingsFor(List.of(PRODUCT_ID, 43L));

        assertThat(ratings).containsOnlyKeys(PRODUCT_ID);
        assertThat(ratings.get(PRODUCT_ID).average()).isEqualTo(5.0);
        assertThat(ratings.get(PRODUCT_ID).count()).isEqualTo(2L);
    }

    @Test
    void ratingsForEmptyInputSkipsQuery() {
        assertThat(service.ratingsFor(List.of())).isEmpty();
    }
}
