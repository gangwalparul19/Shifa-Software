package com.shifa.oms.quikshipx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One persisted QuikShipX courier scan event for an order — our stored mirror of
 * a QuikShipX {@code track-order-v1} {@code shipment_scanning} entry (public
 * order-tracking feature, Option B).
 *
 * <p>The courier scan timeline is only returned by a live QuikShipX track call;
 * to let the public {@code /track-order} page render the detailed timeline
 * (status + location + instruction + time) without a live API call per anonymous
 * visit, the whole set of scans for an order is persisted here and REPLACED on
 * each successful track (the QuikShipX response is the full timeline). Written by
 * the scheduled courier poller and the admin live-tracking view; read by the
 * public token endpoint. Keyed by {@code order_id}; {@link #sortOrder} preserves
 * the newest-first order QuikShipX returns. Mapped to
 * {@code shipment_tracking_events} (V95).
 */
@Entity
@Table(name = "shipment_tracking_events")
public class ShipmentTrackingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** The scan status (QuikShipX {@code status_code_2}, e.g. "In Transit", "Delivered"). */
    @Column(name = "status", length = 120)
    private String status;

    /** The scan location (e.g. "Indore_Dakachya_GW (Madhya Pradesh)"). */
    @Column(name = "location", length = 255)
    private String location;

    /** The scan free-text instruction (e.g. "Out for delivery", "Bag Added To Trip"). */
    @Column(name = "instructions", length = 500)
    private String instructions;

    /** The raw QuikShipX scan timestamp string ({@code scan_dt}); sorts lexically. */
    @Column(name = "scan_at", length = 40)
    private String scanAt;

    /** 0-based position in the timeline as returned by QuikShipX (newest first). */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "synced_at", insertable = false, updatable = false)
    private LocalDateTime syncedAt;

    protected ShipmentTrackingEvent() {
        // Required by JPA.
    }

    public ShipmentTrackingEvent(Long orderId, String status, String location,
                                 String instructions, String scanAt, int sortOrder) {
        this.orderId = orderId;
        this.status = status;
        this.location = location;
        this.instructions = instructions;
        this.scanAt = scanAt;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public String getLocation() {
        return location;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getScanAt() {
        return scanAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
