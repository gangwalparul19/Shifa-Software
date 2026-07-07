package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A persisted order line, mapped to the {@code line_items} table (design data
 * model). Distinct from the pure pricing record
 * {@link com.shifa.oms.order.domain.LineItem}: this is the JPA entity that
 * snapshots the product name and the applied rate at order time (Req 7.3), plus
 * the computed {@code line_total = rate × quantity}.
 *
 * <p>Owned by {@link OrderEntity} through a unidirectional {@code @OneToMany}
 * with a {@code order_id} join column, so it carries no back-reference field.
 */
@Entity
@Table(name = "line_items")
public class OrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /**
     * The product's HSN code snapshotted at order time (Wave 3, Feature 2), so
     * the invoice shows the correct per-line HSN even if the product later
     * changes. Null on legacy rows / products without an HSN.
     */
    @Column(name = "hsn_code", length = 20)
    private String hsnCode;

    /**
     * The product's GST rate percent snapshotted at order time (Wave 3, Feature
     * 2). Null on legacy rows / products without a per-product rate, in which case
     * the invoice falls back to the settings-level default rate.
     */
    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected OrderLineItem() {
        // Required by JPA.
    }

    public OrderLineItem(Long productId, String productName, int quantity, BigDecimal rate, BigDecimal lineTotal) {
        this(productId, productName, null, null, quantity, rate, lineTotal);
    }

    /**
     * Full constructor snapshotting the product's HSN code + GST rate onto the
     * line at order time (Wave 3, Feature 2).
     */
    public OrderLineItem(Long productId, String productName, String hsnCode, BigDecimal gstRate,
                         int quantity, BigDecimal rate, BigDecimal lineTotal) {
        this.productId = productId;
        this.productName = productName;
        this.hsnCode = hsnCode;
        this.gstRate = gstRate;
        this.quantity = quantity;
        this.rate = rate;
        this.lineTotal = lineTotal;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
