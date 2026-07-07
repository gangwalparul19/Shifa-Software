package com.shifa.oms.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An image belonging to a {@link Product}, mapped to the {@code product_images}
 * table (see the V1 Flyway migration).
 *
 * <p>The {@link #published} flag drives the storefront placeholder logic
 * (Req 1.4): a product with no published image is shown with a placeholder in
 * the UI. {@link #sortOrder} determines display order, so the primary image is
 * the published one with the lowest sort order.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ProductImage() {
        // Required by JPA.
    }

    public ProductImage(Long productId, String objectKey, boolean published, int sortOrder) {
        this.productId = productId;
        this.objectKey = objectKey;
        this.published = published;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public boolean isPublished() {
        return published;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
