package com.shifa.oms.review.dto;

import com.shifa.oms.review.ProductReview;
import com.shifa.oms.review.ReviewStatus;

/**
 * The result returned to a customer after submitting (or re-submitting) a
 * review. Confirms the persisted rating and that it is {@link ReviewStatus#PENDING}
 * moderation, plus whether it was flagged as a verified purchase.
 */
public record ReviewSubmissionResponse(
        Long id,
        Long productId,
        int rating,
        String title,
        String body,
        boolean verified,
        ReviewStatus status) {

    public static ReviewSubmissionResponse from(ProductReview review) {
        return new ReviewSubmissionResponse(
                review.getId(),
                review.getProductId(),
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                review.isVerified(),
                review.getStatus());
    }
}
