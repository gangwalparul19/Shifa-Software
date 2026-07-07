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

    public void setShippingLabelKey(String shippingLabelKey) {
        this.shippingLabelKey = shippingLabelKey;
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

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public String getLastCourierStatus() {
        return lastCourierStatus;
    }
}
