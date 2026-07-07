package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order-level payment capture, mapped to the {@code payments} table (one per
 * order in v1). Records how much the customer paid at entry and the storage key
 * of the mandatory payment screenshot when money was received (Req 7.6, 7.11).
 *
 * <p>Owned by {@link OrderEntity} through a unidirectional {@code @OneToMany}
 * with an {@code order_id} join column. {@code captured_at} is filled by the DB
 * default, so it is not written on insert.
 */
@Entity
@Table(name = "payments")
public class OrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountReceived = BigDecimal.ZERO;

    @Column(name = "screenshot_key", length = 512)
    private String screenshotKey;

    @Column(name = "captured_at", insertable = false, updatable = false)
    private LocalDateTime capturedAt;

    protected OrderPayment() {
        // Required by JPA.
    }

    public OrderPayment(BigDecimal amountReceived, String screenshotKey) {
        this.amountReceived = amountReceived;
        this.screenshotKey = screenshotKey;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getAmountReceived() {
        return amountReceived;
    }

    public String getScreenshotKey() {
        return screenshotKey;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }
}
