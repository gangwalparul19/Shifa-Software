package com.shifa.oms.review.dto;

import com.shifa.oms.review.ProductReview;
import com.shifa.oms.review.ReviewStatus;

import java.time.LocalDateTime;

/**
 * A review as shown in the admin moderation queue. Carries the full moderation
 * context (status, verified flag, moderation stamps) plus the product name so
 * the queue is readable without a second lookup per row.
 */
public record AdminReviewResponse(
        Long id,
        Long productId,
        String productName,
        Long userId,
        String authorName,
        int rating,
        String title,
        String body,
        boolean verified,
        ReviewStatus status,
        LocalDateTime createdAt,
        LocalDateTime moderatedAt,
        Long moderatedBy) {

    public static AdminReviewResponse from(ProductReview review, String productName) {
        return new AdminReviewResponse(
                review.getId(),
                review.getProductId(),
                productName,
                review.getUserId(),
                review.getAuthorName(),
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                review.isVerified(),
                review.getStatus(),
                review.getCreatedAt(),
                review.getModeratedAt(),
                review.getModeratedBy());
    }
}
