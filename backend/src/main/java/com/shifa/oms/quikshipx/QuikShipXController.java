package com.shifa.oms.quikshipx;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Admin operational endpoints for the QuikShipX shipment mirror: inspect a
 * shipment's QuikShipX state and re-trigger publication (e.g. after enabling the
 * integration on an order that was punched while it was off, or after a terminal
 * create failure). The routine create/confirm/allot/track flow is automatic; this
 * is only a manual override.
 */
@RestController
@RequestMapping("/api/orders/{orderId}/quikshipx")
@PreAuthorize("hasRole('ADMIN')")
public class QuikShipXController {

    private final OrderShipmentRepository shipmentRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final QuikShipXProperties properties;
    private final QuikShipXService quikShipXService;

    public QuikShipXController(OrderShipmentRepository shipmentRepository,
                               OutboxEventPublisher outboxEventPublisher,
                               QuikShipXProperties properties,
                               QuikShipXService quikShipXService) {
        this.shipmentRepository = shipmentRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.properties = properties;
        this.quikShipXService = quikShipXService;
    }

    /**
     * Live tracking for an order: fetches the current QuikShipX status + scan
     * timeline from {@code track-order}, mirrors the status onto the shipment, and
     * applies the mapped internal transition (idempotent). Viewable by any
     * order-handling staff. Returns an empty timeline with a message when the
     * shipment is not yet trackable.
     */
    @GetMapping("/track")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD','PACKING_USER')")
    public QuikShipXService.TrackView track(@PathVariable Long orderId) {
        return quikShipXService.trackLive(orderId);
    }

    /** The QuikShipX shipment mirror for an order (404 when not yet published). */
    @GetMapping
    @Transactional(readOnly = true)
    public ShipmentView get(@PathVariable Long orderId) {
        return shipmentRepository.findByOrderId(orderId)
                .map(ShipmentView::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no QuikShipX shipment yet."));
    }

    /**
     * (Re)enqueues the QuikShipX create-order for an order. Idempotent — the
     * drainer skips orders that already have a shipment. No-op when the
     * integration is disabled.
     */
    @PostMapping("/publish")
    @Transactional
    public PublishAck publish(@PathVariable Long orderId) {
        if (!properties.isEnabled()) {
            return new PublishAck(false, "QuikShipX integration is disabled (app.quikshipx.enabled=false).");
        }
        if (shipmentRepository.existsByOrderId(orderId)) {
            return new PublishAck(false, "This order already has a QuikShipX shipment.");
        }
        outboxEventPublisher.publishQuikShipXCreate(orderId, "order-" + orderId);
        return new PublishAck(true, "Queued for publication to QuikShipX.");
    }

    /** Read projection of an {@link OrderShipment}. */
    public record ShipmentView(
            Long orderId,
            String orderCode,
            String shipperOrderId,
            String quikShipXStatus,
            String awb,
            String courierId,
            String subCourierName,
            String labelUrl,
            boolean test,
            String lastStatusRaw,
            LocalDateTime lastSyncedAt) {

        static ShipmentView from(OrderShipment s) {
            return new ShipmentView(s.getOrderId(), s.getOrderCode(), s.getShipperOrderId(),
                    s.getQuikShipXStatus(), s.getAwb(), s.getCourierId(), s.getSubCourierName(),
                    s.getLabelUrl(), s.isTest(), s.getLastStatusRaw(), s.getLastSyncedAt());
        }
    }

    /** Acknowledgement of a publish request. */
    public record PublishAck(boolean queued, String message) {
    }
}
