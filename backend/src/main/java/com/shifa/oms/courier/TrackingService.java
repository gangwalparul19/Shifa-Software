package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.dto.TrackingResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Builds the public customer tracking view for an order (Req 13.4): current
 * status plus AWB and courier tracking link once a courier is assigned.
 */
@Service
public class TrackingService {

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;

    public TrackingService(OrderRepository orderRepository,
                           CourierRecordRepository courierRecordRepository,
                           CourierCompanyRepository courierCompanyRepository) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
    }

    /**
     * Tracking details for an order by its code (Req 13.4).
     *
     * @param orderCode the order code
     * @return the tracking projection
     * @throws ResourceNotFoundException when no order has that code
     */
    @Transactional(readOnly = true)
    public TrackingResponse track(String orderCode) {
        OrderEntity order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No order found for code " + orderCode + "."));

        ShipmentInfo shipment = shipmentFor(order.getId()).orElse(null);
        if (shipment == null) {
            return new TrackingResponse(order.getOrderCode(), order.getOrderStatus(), null, null, null);
        }
        return new TrackingResponse(order.getOrderCode(), order.getOrderStatus(),
                shipment.awb(), shipment.courierName(), shipment.trackingUrl());
    }

    /**
     * Customer-facing tracking view resolved by the opaque per-order token
     * (ENHANCEMENT 2.2) — the token-gated form of {@link #track(String)} that
     * cannot be enumerated. Returns a friendly status + a simple stage timeline
     * plus the shipment tracking link once available.
     *
     * @param token the opaque tracking token
     * @return the customer tracking projection
     * @throws ResourceNotFoundException when no order has that token
     */
    @Transactional(readOnly = true)
    public com.shifa.oms.courier.dto.PublicTrackingResponse trackByToken(String token) {
        OrderEntity order = orderRepository.findByTrackingToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("No order found for this tracking link."));

        com.shifa.oms.statemachine.OrderStatus status = order.getOrderStatus();
        com.shifa.oms.order.OrderStatusGroup group =
                com.shifa.oms.order.OrderStatusGroup.groupOf(status);
        ShipmentInfo shipment = shipmentFor(order.getId()).orElse(null);

        boolean delivered = group == com.shifa.oms.order.OrderStatusGroup.DELIVERED;
        boolean failed = group == com.shifa.oms.order.OrderStatusGroup.FAILED_RETURNED
                || group == com.shifa.oms.order.OrderStatusGroup.CANCELLED
                || group == com.shifa.oms.order.OrderStatusGroup.REJECTED;

        return new com.shifa.oms.courier.dto.PublicTrackingResponse(
                order.getOrderCode(),
                firstName(order.getCustomerName()),
                statusLabel(group, status),
                group == null ? null : group.name(),
                shipment != null ? shipment.awb() : null,
                shipment != null ? shipment.courierName() : null,
                shipment != null ? shipment.trackingUrl() : null,
                shipment != null ? shipment.estimatedDelivery() : null,
                delivered,
                timeline(group, failed));
    }

    /** A plain-English status for the customer, from the coarse lifecycle group. */
    private static String statusLabel(com.shifa.oms.order.OrderStatusGroup group,
                                      com.shifa.oms.statemachine.OrderStatus status) {
        if (group == null) {
            return "In progress";
        }
        return switch (group) {
            case PENDING_APPROVAL -> "Order received — being confirmed";
            case PROCESSING -> "Being prepared for shipment";
            case SHIPPED -> status == com.shifa.oms.statemachine.OrderStatus.OUT_FOR_DELIVERY
                    ? "Out for delivery" : "Shipped — on the way";
            case DELIVERED -> "Delivered";
            case FAILED_RETURNED -> "Delivery could not be completed";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Cancelled";
        };
    }

    /** The happy-path stage timeline with each step flagged reached/pending. */
    private static List<com.shifa.oms.courier.dto.PublicTrackingResponse.Step> timeline(
            com.shifa.oms.order.OrderStatusGroup group, boolean failed) {
        // Ordinal of the current group on the happy path (−1 for failed/unknown).
        int reachedUpTo = switch (group == null ? com.shifa.oms.order.OrderStatusGroup.PENDING_APPROVAL : group) {
            case PENDING_APPROVAL -> 0;
            case PROCESSING -> 1;
            case SHIPPED -> 2;
            case DELIVERED -> 3;
            default -> failed ? -1 : 0;
        };
        String[] labels = {"Order placed", "Being prepared", "Shipped", "Delivered"};
        List<com.shifa.oms.courier.dto.PublicTrackingResponse.Step> steps =
                new java.util.ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            steps.add(new com.shifa.oms.courier.dto.PublicTrackingResponse.Step(
                    labels[i], reachedUpTo >= i));
        }
        return steps;
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        String trimmed = fullName.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /**
     * Builds the {@link ShipmentInfo} for an order id, reusing the same
     * courier-record lookup and tracking-link building as the public tracking
     * view (Req 13.4). Returns {@link Optional#empty()} when no courier record
     * with an AWB exists for the order, so the caller can leave shipment fields
     * unset. Shared by the admin order-detail response.
     */
    @Transactional(readOnly = true)
    public Optional<ShipmentInfo> shipmentFor(Long orderId) {
        Optional<CourierRecord> record = courierRecordRepository.findByOrderId(orderId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        CourierRecord cr = record.get();
        boolean hasAwb = cr.getAwb() != null && !cr.getAwb().isBlank();
        boolean hasDirectUrl = cr.getTrackingUrl() != null && !cr.getTrackingUrl().isBlank();
        // Trackable when the order has either a tracking id (AWB) or a direct
        // vendor tracking link. An in-house delivery partner may supply a
        // ready-made link with no clean AWB, so a blank AWB alone no longer means
        // "nothing to track" (in-house delivery-partner feature).
        if (!hasAwb && !hasDirectUrl) {
            return Optional.empty();
        }

        String courierName = null;
        String templateUrl = null;
        if (cr.getCourierCompanyId() != null) {
            Optional<CourierCompany> company = courierCompanyRepository.findById(cr.getCourierCompanyId());
            if (company.isPresent()) {
                courierName = company.get().getName();
                templateUrl = company.get().trackingUrl(cr.getAwb());
            }
        }
        // A vendor-provided direct link (stored verbatim on the record) wins over
        // the per-company {awb} template; fall back to the template otherwise.
        String trackingUrl = hasDirectUrl ? cr.getTrackingUrl() : templateUrl;
        return Optional.of(new ShipmentInfo(
                cr.getAwb(), courierName, trackingUrl, cr.getEstimatedDelivery()));
    }

    /**
     * The assigned delivery partner's name for an order, <em>independent of
     * whether an AWB exists</em> (in-house-delivery feature).
     *
     * <p>{@link #shipmentFor} deliberately returns empty without an AWB (the
     * public tracking view has nothing to track), but a partner can now be
     * recorded with no tracking number at all — e.g. a parcel handed to a local
     * operator or sent by bus. This lets the admin order-detail view still show
     * who has the parcel. Returns {@link Optional#empty()} when no courier record
     * / company is recorded for the order.
     */
    @Transactional(readOnly = true)
    public Optional<String> courierNameFor(Long orderId) {
        return courierRecordRepository.findByOrderId(orderId)
                .map(CourierRecord::getCourierCompanyId)
                .flatMap(companyId -> companyId == null
                        ? Optional.empty() : courierCompanyRepository.findById(companyId))
                .map(CourierCompany::getName);
    }
}
