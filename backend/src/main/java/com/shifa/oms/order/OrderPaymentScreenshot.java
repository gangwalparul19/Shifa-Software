package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One payment proof attached to an order, mapped to the
 * {@code order_payment_screenshots} table (V65).
 *
 * <p>An order may carry several proofs — a part payment plus the balance, a UPI
 * receipt plus a bank confirmation, or simply two screenshots because the
 * transaction did not fit one screen. Each row holds the opaque
 * {@code storage_key} returned by {@link com.shifa.oms.platform.storage.StorageService}
 * (never the bytes) plus the client-reported filename / MIME type / size, which
 * let the viewer label and lay out thumbnails without fetching every object.
 *
 * <p>{@code sortOrder} preserves the upload order so the proofs are always shown
 * in the sequence the salesperson attached them; index 0 is the primary proof and
 * is ALSO mirrored onto the legacy single-valued
 * {@code orders.payment_screenshot_key} column for backward compatibility.
 *
 * <p>Owned by {@link OrderEntity} through a unidirectional {@code @OneToMany}
 * with an {@code order_id} join column, matching {@link OrderLineItem} /
 * {@link OrderPayment}. {@code created_at} is filled by the DB default, so it is
 * not written on insert.
 */
@Entity
@Table(name = "order_payment_screenshots")
public class OrderPaymentScreenshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "filename", length = 255)
    private String filename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderPaymentScreenshot() {
        // Required by JPA.
    }

    public OrderPaymentScreenshot(String storageKey, String filename, String contentType,
                                 Long byteSize, int sortOrder) {
        this.storageKey = storageKey;
        this.filename = filename;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
