package com.shifa.oms.review;

/**
 * Moderation lifecycle of a {@link ProductReview}.
 *
 * <p>A review is created {@link #PENDING} and awaits admin moderation. Approving
 * moves it to {@link #APPROVED} (visible on the storefront); rejecting moves it
 * to {@link #REJECTED} (never shown). Only {@link #APPROVED} reviews contribute
 * to a product's public average rating and count.
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
