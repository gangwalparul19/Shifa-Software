package com.shifa.oms.review.dto;

import com.shifa.oms.review.ProductReview;

import java.time.LocalDateTime;

/**
 * A single APPROVED review as shown on the public product page. Deliberately
 * omits the author's user id and moderation metadata — only display fields.
 */
public record ReviewResponse(
        Long id,
        String authorName,
        int rating,
        String title,
        String body,
        boolean verified,
        LocalDateTime createdAt) {

    public static ReviewResponse from(ProductReview review) {
        return new ReviewResponse(
                review.getId(),
                review.getAuthorName(),
                review.getRating(),
                review.getTitle(),
                review.getBody(),
                review.isVerified(),
                review.getCreatedAt());
    }
}
