package com.shifa.oms.integration.shopify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One reason an ingested Shopify order needs a human look, mapped to
 * {@code order_review_reasons} (V49).
 *
 * <p>A child table rather than a column on {@code orders} because
 * {@code UNIQUE(order_id, reason)} makes "record each reason exactly once" an enforced
 * database invariant (Req 3.12). A comma-separated column would need read-modify-write and
 * would lose a reason under a concurrent re-processing of the same webhook.
 */
@Entity
@Table(name = "order_review_reasons")
public class OrderReviewReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private ReviewReason reason;

    /** What specifically was wrong, e.g. the unmatched SKU. Helps a human fix it. */
    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderReviewReason() {
        // Required by JPA.
    }

    public OrderReviewReason(Long orderId, ReviewReason reason, String detail) {
        this.orderId = orderId;
        this.reason = reason;
        this.detail = truncate(detail);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ReviewReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
