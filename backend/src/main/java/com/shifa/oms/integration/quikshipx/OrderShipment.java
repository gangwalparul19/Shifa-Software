package com.shifa.oms.integration.quikshipx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The QuikShipX shipment record for one order, mapped to {@code order_shipments} (V49).
 *
 * <p>Its existence is what makes an order "QuikShipX-managed": once a row is here,
 * QuikShipX owns the label and the fulfilment status, human transitions are denied and
 * the order leaves the packing queue.
 *
 * <p>{@code UNIQUE(order_id)} makes publication idempotence a database invariant rather
 * than a code convention — two concurrent drainer cycles cannot both create a shipment.
 *
 * <p>{@link #orderReference} is the value we sent as {@code customer_order_id} and is
 * {@code NOT NULL}, whereas {@link #quikshipxShipmentId} is nullable. That looks
 * backwards until you remember the create-order response is undocumented: the reference
 * is the only identifier we are guaranteed to have, so it, not QuikShipX's id, is the
 * reliable correlation key.
 */
@Entity
@Table(name = "order_shipments")
public class OrderShipment {

    /**
     * QuikShipX's initial order status. An order that QuikShipX has accepted sits in their
     * {@code Pending} section until the packing team prints the label and marks it ready, so
     * this is the status Shifa shows the moment publication succeeds.
     */
    public static final String INITIAL_STATUS = "Pending";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "order_reference", nullable = false, unique = true, length = 80)
    private String orderReference;

    /** QuikShipX's shipment/tracking identifier ({@code id}); absent when none returned. */
    @Column(name = "quikshipx_shipment_id", length = 80)
    private String quikshipxShipmentId;

    /**
     * QuikShipX's own order id ({@code order_id} in their create-order response, e.g.
     * {@code 177286}), distinct from our {@link #orderReference} and from
     * {@link #quikshipxShipmentId}. This is the id to quote when tracking the order in the
     * QuikShipX portal, so it is stored on our side for cross-referencing (V51).
     */
    @Column(name = "quikshipx_order_id", length = 80)
    private String quikshipxOrderId;

    @Column(name = "awb", length = 60)
    private String awb;

    @Column(name = "courier_name", length = 120)
    private String courierName;

    @Column(name = "tracking_url", length = 500)
    private String trackingUrl;

    /**
     * A label URL, only if the create response happened to return one. QuikShipX
     * documents no label endpoint, so normally the packer downloads the label from the
     * QuikShipX portal and this stays null.
     */
    @Column(name = "label_url", length = 500)
    private String labelUrl;

    /** The last QuikShipX status token mirrored into Shifa, once a status feed exists. */
    @Column(name = "last_status_token", length = 80)
    private String lastStatusToken;

    /** Timestamp of that status; monotonic, which is what rejects out-of-order events. */
    @Column(name = "last_status_at")
    private LocalDateTime lastStatusAt;

    /** Booked with the TEST secret, so it lives in QuikShipX's Test section. */
    @Column(name = "is_test", nullable = false)
    private boolean test = false;

    /** The create-order response verbatim, for pinning the real identifier key names. */
    @Column(name = "raw_acceptance", columnDefinition = "TEXT")
    private String rawAcceptance;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected OrderShipment() {
        // Required by JPA.
    }

    public OrderShipment(Long orderId, String orderReference) {
        this.orderId = orderId;
        this.orderReference = orderReference;
    }

    /** Builds a shipment record from what QuikShipX gave back. */
    public static OrderShipment from(Long orderId, ShipmentAcceptance acceptance) {
        OrderShipment shipment = new OrderShipment(orderId, acceptance.orderReference());
        shipment.applyAcceptance(acceptance);
        return shipment;
    }

    /**
     * Copies the acceptance identifiers, leaving absent values absent rather than
     * writing empty strings — the UI distinguishes "no AWB yet" from "AWB is blank".
     */
    public void applyAcceptance(ShipmentAcceptance acceptance) {
        this.quikshipxShipmentId = acceptance.shipmentIdValue().orElse(null);
        this.quikshipxOrderId = acceptance.quikshipxOrderIdValue().orElse(null);
        this.awb = acceptance.awbValue().orElse(null);
        this.courierName = acceptance.courierNameValue().orElse(null);
        this.trackingUrl = acceptance.trackingUrlValue().orElse(null);
        this.labelUrl = acceptance.labelUrlValue().orElse(null);
        this.test = acceptance.test();
        this.rawAcceptance = acceptance.rawResponse();
    }

    /**
     * Advances the mirrored status.
     *
     * <p>Refuses to move the timestamp backwards, so the monotonicity invariant holds
     * even if events arrive out of order (Req 6.8).
     *
     * @return true when the status was advanced
     */
    public boolean advanceStatus(String token, LocalDateTime at) {
        if (at == null) {
            return false;
        }
        if (lastStatusAt != null && !at.isAfter(lastStatusAt)) {
            return false;
        }
        this.lastStatusToken = token;
        this.lastStatusAt = at;
        return true;
    }

    /**
     * Records the AWB once QuikShipX assigns it (it is absent at order creation and appears
     * later). Idempotent and one-way: a non-blank AWB is set only when we do not already hold
     * one, so a later blank reading cannot wipe a known AWB.
     *
     * @return true when the AWB was newly recorded
     */
    public boolean assignAwb(String newAwb) {
        if (newAwb == null || newAwb.isBlank()) {
            return false;
        }
        if (this.awb != null && !this.awb.isBlank()) {
            return false;
        }
        this.awb = newAwb.trim();
        return true;
    }

    /**
     * Sets (or corrects) the AWB from an admin who read it off the QuikShipX portal.
     * Unlike {@link #assignAwb(String)} this overwrites an existing value, because the
     * QuikShipX create-order response is undocumented and often carries no AWB, so the
     * admin is the source of truth here. A blank value is ignored so a stray empty save
     * cannot wipe a known AWB.
     *
     * @return true when a non-blank AWB was applied
     */
    public boolean overwriteAwb(String newAwb) {
        if (newAwb == null || newAwb.isBlank()) {
            return false;
        }
        this.awb = newAwb.trim();
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public String getQuikshipxShipmentId() {
        return quikshipxShipmentId;
    }

    public String getQuikshipxOrderId() {
        return quikshipxOrderId;
    }

    public String getAwb() {
        return awb;
    }

    public String getCourierName() {
        return courierName;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public String getLabelUrl() {
        return labelUrl;
    }

    public String getLastStatusToken() {
        return lastStatusToken;
    }

    public LocalDateTime getLastStatusAt() {
        return lastStatusAt;
    }

    public boolean isTest() {
        return test;
    }

    public String getRawAcceptance() {
        return rawAcceptance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
