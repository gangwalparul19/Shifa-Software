package com.shifa.oms.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A customer-submitted product review, mapped to the {@code product_reviews}
 * table (V5 migration).
 *
 * <p>A review is written by an authenticated customer, snapshotting their
 * display name ({@link #authorName}) and linking their {@link #userId}. It
 * carries a {@link #rating} (1..5), an optional {@link #title}/{@link #body},
 * and a {@link #verified} flag computed from order history at submit time (did
 * this customer actually purchase this product). It starts {@link ReviewStatus#PENDING}
 * and only becomes visible to the storefront once an admin approves it.
 */
@Entity
@Table(name = "product_reviews")
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** The authoring customer's user id; nullable at the schema level but always set here. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "author_name", nullable = false, length = 120)
    private String authorName;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "title", length = 150)
    private String title;

    @Column(name = "body", length = 2000)
    private String body;

    /** Whether the author purchased this product (verified-purchase badge). */
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    @Column(name = "moderated_by")
    private Long moderatedBy;

    protected ProductReview() {
        // Required by JPA.
    }

    public ProductReview(Long productId, Long userId, String authorName, int rating,
                         String title, String body, boolean verified) {
        this.productId = productId;
        this.userId = userId;
        this.authorName = authorName;
        this.rating = rating;
        this.title = title;
        this.body = body;
        this.verified = verified;
        this.status = ReviewStatus.PENDING;
    }

    /** Populates the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * Approves this review, stamping the moderator and time and clearing any
     * prior rejection. Idempotent with respect to the resulting state.
     */
    public void approve(Long moderatorUserId, LocalDateTime when) {
        this.status = ReviewStatus.APPROVED;
        this.moderatedBy = moderatorUserId;
        this.moderatedAt = when;
    }

    /** Rejects this review, stamping the moderator and time. */
    public void reject(Long moderatorUserId, LocalDateTime when) {
        this.status = ReviewStatus.REJECTED;
        this.moderatedBy = moderatorUserId;
        this.moderatedAt = when;
    }

    /**
     * Re-submits an existing review with fresh content, returning it to the
     * moderation queue (PENDING) and clearing prior moderation stamps.
     */
    public void resubmit(int rating, String title, String body, boolean verified) {
        this.rating = rating;
        this.title = title;
        this.body = body;
        this.verified = verified;
        this.status = ReviewStatus.PENDING;
        this.moderatedAt = null;
        this.moderatedBy = null;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public int getRating() {
        return rating;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public boolean isVerified() {
        return verified;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getModeratedAt() {
        return moderatedAt;
    }

    public Long getModeratedBy() {
        return moderatedBy;
    }
}
