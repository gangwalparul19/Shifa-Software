package com.shifa.oms.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * The courier shipment record for an order, mapped to the {@code courier_records}
 * table (design data model). One record per order ({@code order_id} unique)
 * holding the assigned AWB (Req 12.2), the stored shipping-label PDF key, the
 * estimated delivery date (Req 14.1), and the last raw courier status seen from
 * a webhook/poll (for idempotency and display).
 */
@Entity
@Table(name = "courier_records")
public class CourierRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "courier_company_id")
    private Long courierCompanyId;

    @Column(name = "awb", length = 64)
    private String awb;

    @Column(name = "shipping_label_key", length = 512)
    private String shippingLabelKey;

    /**
     * A vendor-provided, ready-made tracking link for an in-house delivery partner
     * (in-house delivery-partner feature, V76). Stored verbatim — it may not fit a
     * per-carrier {@code {awb}} template — and, when present, takes precedence over
     * the courier company's {@code tracking_url_template}. Null for QuikShipX /
     * automated-courier orders, which build their link from the company template.
     */
    @Column(name = "tracking_url", length = 500)
    private String trackingUrl;

    @Column(name = "estimated_delivery")
    private LocalDate estimatedDelivery;

    @Column(name = "last_courier_status", length = 40)
    private String lastCourierStatus;

    protected CourierRecord() {
        // Required by JPA.
    }

    public CourierRecord(Long orderId) {
        this.orderId = orderId;
    }

    public void assign(Long courierCompanyId, String awb, String shippingLabelKey,
                       LocalDate estimatedDelivery) {
        this.courierCompanyId = courierCompanyId;
        this.awb = awb;
        this.shippingLabelKey = shippingLabelKey;
        this.estimatedDelivery = estimatedDelivery;
    }

    /**
     * As {@link #assign(Long, String, String, LocalDate)} but also records a
     * vendor-provided direct tracking link (in-house delivery-partner feature).
     */
    public void assign(Long courierCompanyId, String awb, String shippingLabelKey,
                       LocalDate estimatedDelivery, String trackingUrl) {
        assign(courierCompanyId, awb, shippingLabelKey, estimatedDelivery);
        this.trackingUrl = trackingUrl;
    }

    public void setShippingLabelKey(String shippingLabelKey) {
        this.shippingLabelKey = shippingLabelKey;
    }

    public void setTrackingUrl(String trackingUrl) {
        this.trackingUrl = trackingUrl;
    }

    public void setLastCourierStatus(String lastCourierStatus) {
        this.lastCourierStatus = lastCourierStatus;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getCourierCompanyId() {
        return courierCompanyId;
    }

    public String getAwb() {
        return awb;
    }

    public String getShippingLabelKey() {
        return shippingLabelKey;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public String getLastCourierStatus() {
        return lastCourierStatus;
    }
}
