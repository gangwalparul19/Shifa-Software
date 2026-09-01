package com.shifa.oms.quikshipx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The QuikShipX-side view of a Shifa order: the identifiers and mirrored status
 * of the shipment QuikShipX holds for it. One row per order (unique
 * {@code order_id}), created when the order is first published to QuikShipX.
 *
 * <p>Fields fill in over the shipment lifecycle:
 * <ul>
 *   <li>on create-order: {@link #shipperOrderId} (QuikShipX's own order id, the
 *       number quoted when allotting a tracking id) + {@link #quikShipXStatus} =
 *       {@code Pending} + {@link #test};</li>
 *   <li>on admin approval: {@link #quikShipXStatus} = {@code Confirmed};</li>
 *   <li>on allot-tracking-id: {@link #awb}, {@link #courierId},
 *       {@link #subCourierName}, {@link #labelUrl} + status
 *       {@code Tracking ID Assigned};</li>
 *   <li>on track-order polls: {@link #quikShipXStatus} / {@link #lastStatusRaw} /
 *       {@link #lastSyncedAt}.</li>
 * </ul>
 */
@Entity
@Table(name = "order_shipments")
public class OrderShipment {

    /** QuikShipX status after a successful create-order (their "Pending" section). */
    public static final String STATUS_PENDING = "Pending";
    /** QuikShipX status mirrored when the order is approved by an admin. */
    public static final String STATUS_CONFIRMED = "Confirmed";
    /** QuikShipX status after a tracking id (AWB) has been allotted. */
    public static final String STATUS_TRACKING_ID_ASSIGNED = "Tracking ID Assigned";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** Denormalised order code so the client can look a shipment up without the order aggregate. */
    @Column(name = "order_code", nullable = false, length = 40)
    private String orderCode;

    /** QuikShipX's own order id (their {@code shipper_order_id}); the allot key. */
    @Column(name = "shipper_order_id", length = 64)
    private String shipperOrderId;

    /** The mirrored QuikShipX status label (e.g. {@code Pending}, {@code Confirmed}). */
    @Column(name = "quikshipx_status", length = 40)
    private String quikShipXStatus;

    @Column(name = "awb", length = 64)
    private String awb;

    @Column(name = "courier_id", length = 16)
    private String courierId;

    @Column(name = "sub_courier_name", length = 80)
    private String subCourierName;

    /** The QuikShipX-hosted shipping-label PDF URL returned when a tracking id is allotted. */
    @Column(name = "label_url", length = 1000)
    private String labelUrl;

    /** Whether this shipment was booked with the TEST secret (QuikShipX Test section). */
    @Column(name = "is_test", nullable = false)
    private boolean test;

    /** The raw QuikShipX {@code order_status} text last seen while tracking. */
    @Column(name = "last_status_raw", length = 120)
    private String lastStatusRaw;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected OrderShipment() {
        // Required by JPA.
    }

    public OrderShipment(Long orderId, String orderCode) {
        this.orderId = orderId;
        this.orderCode = orderCode;
    }

    /** Records the QuikShipX create-order acceptance (their "Pending" section). */
    public void recordCreated(String shipperOrderId, boolean test) {
        this.shipperOrderId = shipperOrderId;
        this.test = test;
        this.quikShipXStatus = STATUS_PENDING;
    }

    /** Records the allotted tracking id (AWB), courier, and label URL. */
    public void recordTrackingId(String awb, String courierId, String subCourierName, String labelUrl) {
        this.awb = awb;
        this.courierId = courierId;
        this.subCourierName = subCourierName;
        this.labelUrl = labelUrl;
        this.quikShipXStatus = STATUS_TRACKING_ID_ASSIGNED;
    }

    /** Mirrors a QuikShipX status label onto the shipment. */
    public void setQuikShipXStatus(String status) {
        this.quikShipXStatus = status;
    }

    /** Records the latest raw tracking status seen and the sync time. */
    public void recordTracked(String rawStatus, String mappedStatusLabel, LocalDateTime at) {
        this.lastStatusRaw = rawStatus;
        if (mappedStatusLabel != null && !mappedStatusLabel.isBlank()) {
            this.quikShipXStatus = mappedStatusLabel;
        }
        this.lastSyncedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderCode() {
        return orderCode;
    }

    public String getShipperOrderId() {
        return shipperOrderId;
    }

    public String getQuikShipXStatus() {
        return quikShipXStatus;
    }

    public String getAwb() {
        return awb;
    }

    public String getCourierId() {
        return courierId;
    }

    public String getSubCourierName() {
        return subCourierName;
    }

    public String getLabelUrl() {
        return labelUrl;
    }

    public boolean isTest() {
        return test;
    }

    public String getLastStatusRaw() {
        return lastStatusRaw;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
