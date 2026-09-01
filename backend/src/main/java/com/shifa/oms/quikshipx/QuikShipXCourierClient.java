package com.shifa.oms.quikshipx;

import com.shifa.oms.courier.CourierAssignmentRequest;
import com.shifa.oms.courier.CourierAssignmentResult;
import com.shifa.oms.courier.CourierClient;
import com.shifa.oms.courier.CourierClientException;
import com.shifa.oms.courier.CourierTrackingEvent;
import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.TrackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Bridges QuikShipX into the existing {@link CourierClient} seam so the whole
 * dispatch + tracking machinery (packing dispatch → {@code COURIER_ASSIGN}
 * outbox → {@code CourierAssignmentService}; and the scheduled
 * {@code CourierTrackingPoller} → {@code CourierStatusApplier}) drives QuikShipX
 * with no changes to those classes.
 *
 * <ul>
 *   <li>{@link #assign} → QuikShipX <b>allot-tracking-id</b>: allots the AWB +
 *       label using the shipment's stored QuikShipX order id, records them on the
 *       {@link OrderShipment}, and returns the AWB so the caller advances the
 *       order to {@code Courier_Assigned} (QuikShipX "Tracking ID Assigned").</li>
 *   <li>{@link #pollLatest} → QuikShipX <b>track-order</b>: reads the current
 *       status, mirrors it onto the shipment, and returns it for
 *       {@code CourierStatusApplier} to apply.</li>
 * </ul>
 *
 * <p>Active (and {@link Primary}) only when {@code app.quikshipx.enabled=true};
 * otherwise the existing {@code MockCourierClient} remains the courier client.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.quikshipx.enabled", havingValue = "true")
public class QuikShipXCourierClient implements CourierClient {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXCourierClient.class);

    private final QuikShipXClient client;
    private final OrderShipmentRepository shipmentRepository;

    public QuikShipXCourierClient(QuikShipXClient client, OrderShipmentRepository shipmentRepository) {
        this.client = client;
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional
    public CourierAssignmentResult assign(CourierAssignmentRequest request) {
        OrderShipment shipment = shipmentRepository.findByOrderCode(request.orderCode())
                .orElseThrow(() -> new CourierClientException(
                        "Order " + request.orderCode() + " is not yet published to QuikShipX; "
                                + "cannot allot a tracking id (will retry)."));
        // Idempotent: if a tracking id was already allotted (e.g. at approval),
        // reuse it instead of booking a second shipment.
        if (shipment.getAwb() != null && !shipment.getAwb().isBlank()) {
            String courier = (shipment.getSubCourierName() == null || shipment.getSubCourierName().isBlank())
                    ? "QuikShipX" : shipment.getSubCourierName();
            log.info("QuikShipX AWB {} already allotted for order {}; reusing at dispatch",
                    shipment.getAwb(), request.orderCode());
            return new CourierAssignmentResult(shipment.getAwb(), courier, null);
        }

        String shipperOrderId = shipment.getShipperOrderId();
        if (shipperOrderId == null || shipperOrderId.isBlank()) {
            throw new CourierClientException(
                    "QuikShipX order id is unknown for " + request.orderCode()
                            + "; re-publish to QuikShipX before allotting a tracking id.");
        }

        AllotResult allot;
        try {
            allot = client.allotTrackingId(shipperOrderId);
        } catch (QuikShipXException e) {
            // Surface as the courier failure type so the outbox drainer retries
            // (order retains Handed_To_Delivery) and, once exhausted, notifies admin.
            throw new CourierClientException(e.getMessage(), e);
        }

        shipment.recordTrackingId(allot.awb(), allot.courierId(), allot.subCourierName(), allot.labelUrl());
        shipmentRepository.save(shipment);

        String courierName = (allot.subCourierName() == null || allot.subCourierName().isBlank())
                ? "QuikShipX" : allot.subCourierName();
        log.info("QuikShipX allotted AWB {} to order {} (courier {})",
                allot.awb(), request.orderCode(), courierName);
        // No estimated-delivery in the allot response; tracking fills status later.
        return new CourierAssignmentResult(allot.awb(), courierName, null);
    }

    @Override
    @Transactional
    public Optional<CourierTrackingEvent> pollLatest(String awb) {
        if (awb == null || awb.isBlank()) {
            return Optional.empty();
        }
        TrackResult track;
        try {
            track = client.trackOrder(awb);
        } catch (QuikShipXException e) {
            // Not trackable yet / transient — skip this cycle (the poller retries).
            log.debug("QuikShipX track-order skipped for AWB {}: {}", awb, e.getMessage());
            return Optional.empty();
        }
        String rawStatus = track.orderStatus();
        if (rawStatus == null || rawStatus.isBlank()) {
            return Optional.empty();
        }
        shipmentRepository.findByAwb(awb).ifPresent(shipment -> {
            shipment.recordTracked(rawStatus, titleCase(rawStatus), LocalDateTime.now());
            shipmentRepository.save(shipment);
        });
        return Optional.of(new CourierTrackingEvent(awb, rawStatus));
    }

    /** Title-cases a raw status like "out for delivery" → "Out For Delivery" for display. */
    private static String titleCase(String value) {
        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
