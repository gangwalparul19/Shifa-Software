package com.shifa.oms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A record that a given customer WhatsApp shipment milestone has already been
 * sent for an order (customer shipment notifications) — the de-duplication
 * marker behind "send each milestone exactly once".
 *
 * <p>The courier scan timeline is re-polled every few minutes and a milestone
 * like "Vehicle Departed" can appear several times, so the milestone notifier
 * atomically "claims" a milestone by inserting a row here (guarded by the
 * {@code UNIQUE(order_id, milestone)} constraint, V96) BEFORE enqueuing the
 * WhatsApp message. A duplicate claim fails the unique constraint and the message
 * is skipped, so a repeat/concurrent poll can never send the same milestone twice.
 */
@Entity
@Table(name = "order_milestone_notifications")
public class OrderMilestoneNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** The milestone key (e.g. {@code ORDER_PLACED}, {@code PICKED_UP}, {@code SHIPMENT_DEPARTED}, {@code OUT_FOR_DELIVERY}). */
    @Column(name = "milestone", nullable = false, length = 40)
    private String milestone;

    @Column(name = "notified_at", insertable = false, updatable = false)
    private LocalDateTime notifiedAt;

    protected OrderMilestoneNotification() {
        // Required by JPA.
    }

    public OrderMilestoneNotification(Long orderId, String milestone) {
        this.orderId = orderId;
        this.milestone = milestone;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getMilestone() {
        return milestone;
    }

    public LocalDateTime getNotifiedAt() {
        return notifiedAt;
    }
}
