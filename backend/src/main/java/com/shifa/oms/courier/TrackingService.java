package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.dto.TrackingResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
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
    private final com.shifa.oms.quikshipx.ShipmentTrackingEventRepository trackingEventRepository;
    private final com.shifa.oms.settings.SettingsService settingsService;

    @org.springframework.beans.factory.annotation.Autowired
    public TrackingService(OrderRepository orderRepository,
                           CourierRecordRepository courierRecordRepository,
                           CourierCompanyRepository courierCompanyRepository,
                           com.shifa.oms.quikshipx.ShipmentTrackingEventRepository trackingEventRepository,
                           com.shifa.oms.settings.SettingsService settingsService) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.settingsService = settingsService;
    }

    /**
     * Legacy/test constructor without the stored tracking-event repository or
     * settings — the detailed courier scan timeline is then empty and the brand
     * name falls back to a default (the coarse stage timeline + shipment link
     * still render). Spring always uses the full constructor above.
     */
    public TrackingService(OrderRepository orderRepository,
                           CourierRecordRepository courierRecordRepository,
                           CourierCompanyRepository courierCompanyRepository) {
        this(orderRepository, courierRecordRepository, courierCompanyRepository, null, null);
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

        return new com.shifa.oms.courier.dto.PublicTrackingResponse(
                order.getOrderCode(),
                firstName(order.getCustomerName()),
                statusLabel(group, status),
                group == null ? null : group.name(),
                shipment != null ? shipment.awb() : null,
                shipment != null ? shipment.courierName() : null,
                shipment != null ? shipment.trackingUrl() : null,
                shipment != null ? shipment.estimatedDelivery() : null,
                isDelivered(group),
                timeline(group, isFailed(group)),
                detailedEvents(order.getId()));
    }

    /**
     * The detailed courier scan timeline for an order (newest first), read from
     * the stored {@code shipment_tracking_events} (Option B) — no live QuikShipX
     * call. Empty when none are stored yet, or when the lightweight/test
     * constructor left the event repository unset.
     */
    private List<com.shifa.oms.courier.dto.PublicTrackingResponse.Event> detailedEvents(Long orderId) {
        if (trackingEventRepository == null || orderId == null) {
            return List.of();
        }
        return trackingEventRepository.findByOrderIdOrderBySortOrderAsc(orderId).stream()
                .map(e -> new com.shifa.oms.courier.dto.PublicTrackingResponse.Event(
                        e.getStatus(), e.getInstructions(), e.getLocation(), e.getScanAt()))
                .toList();
    }

    /**
     * The customer's orders for a registered mobile number, as privacy-safe
     * tracking cards (public self-service tracking page {@code /track-order}).
     *
     * <p>Lets a customer (or an agent) look up all their orders by the mobile
     * they registered with — no login — and pick one to see its live tracking,
     * saving a WhatsApp notification. Deliberately minimal and privacy-safe: each
     * card carries only what the tracking list needs (order code, opaque tracking
     * token to open the detail, a short product summary, the friendly status/stage,
     * and the placed-on date) — <em>no money, no address, no mobile echo, no
     * internal id, no salesperson</em>. The status/stage/delivered flags use the
     * same group→label mapping as {@link #trackByToken(String)} so a card and its
     * detail page agree.
     *
     * <p>The mobile is normalised to exactly 10 digits (non-digits stripped). An
     * unknown or malformed number returns an empty list rather than throwing — the
     * page shows a clean "no orders found" state and nothing can be probed. The
     * result is capped to the most recent {@link #MAX_MOBILE_CARDS} orders.
     *
     * @param mobile the raw mobile number the customer typed
     * @return the privacy-safe tracking cards, newest first (possibly empty)
     */
    @Transactional(readOnly = true)
    public com.shifa.oms.courier.dto.TrackByMobileResponse ordersByMobile(String mobile) {
        String normalized = normalizeMobile(mobile);
        if (normalized == null) {
            return new com.shifa.oms.courier.dto.TrackByMobileResponse(brandName(), List.of());
        }
        List<com.shifa.oms.courier.dto.TrackByMobileResponse.OrderCard> cards =
                orderRepository.findByCustomerMobileOrderByCreatedAtDesc(normalized).stream()
                        .limit(MAX_MOBILE_CARDS)
                        .map(TrackingService::toCard)
                        .toList();
        return new com.shifa.oms.courier.dto.TrackByMobileResponse(brandName(), cards);
    }

    /** Cap on the number of tracking cards returned for a mobile lookup. */
    private static final int MAX_MOBILE_CARDS = 25;

    /**
     * Normalises a raw mobile to exactly 10 digits, or {@code null} if it does not
     * reduce to exactly 10 digits after stripping non-digit characters.
     */
    private static String normalizeMobile(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 10 ? digits : null;
    }

    /**
     * The business/brand display name for the public tracking page, read from the
     * company {@link com.shifa.oms.settings.AppSettings} (its {@code legalName}).
     * Falls back to the default brand name when settings are unavailable (e.g. the
     * lightweight/test constructor) or blank, so the page always has a name.
     */
    private String brandName() {
        if (settingsService != null) {
            try {
                String name = settingsService.getSettings().getLegalName();
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (RuntimeException ignored) {
                // Fall through to the default below.
            }
        }
        return "Shifa Herbal Remedies";
    }

    /** Maps one order to a privacy-safe tracking card for the by-mobile list. */
    private static com.shifa.oms.courier.dto.TrackByMobileResponse.OrderCard toCard(OrderEntity order) {
        com.shifa.oms.statemachine.OrderStatus status = order.getOrderStatus();
        com.shifa.oms.order.OrderStatusGroup group =
                com.shifa.oms.order.OrderStatusGroup.groupOf(status);
        return new com.shifa.oms.courier.dto.TrackByMobileResponse.OrderCard(
                order.getOrderCode(),
                order.getTrackingToken(),
                productSummary(order),
                statusLabel(group, status),
                group == null ? null : group.name(),
                isDelivered(group),
                order.getCreatedAt() == null ? null : order.getCreatedAt().toLocalDate());
    }

    /**
     * A short "Amla Juice x2, Neem Caps x1" summary of the order's line items
     * (first few items, length-capped), for the tracking card title. Never null.
     */
    private static String productSummary(OrderEntity order) {
        List<OrderLineItem> lines = order.getLineItems();
        if (lines == null || lines.isEmpty()) {
            return "Your order";
        }
        int shown = Math.min(lines.size(), 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            OrderLineItem line = lines.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(line.getProductName()).append(" x").append(line.getQuantity());
        }
        if (lines.size() > shown) {
            sb.append(" …");
        }
        String summary = sb.toString();
        // Guard against an absurdly long single product name.
        return summary.length() > 120 ? summary.substring(0, 119) + "…" : summary;
    }

    /** Whether the coarse group represents a successfully delivered/closed order. */
    private static boolean isDelivered(com.shifa.oms.order.OrderStatusGroup group) {
        return group == com.shifa.oms.order.OrderStatusGroup.DELIVERED;
    }

    /** Whether the coarse group represents a failed / returned / cancelled / rejected order. */
    private static boolean isFailed(com.shifa.oms.order.OrderStatusGroup group) {
        return group == com.shifa.oms.order.OrderStatusGroup.FAILED_RETURNED
                || group == com.shifa.oms.order.OrderStatusGroup.CANCELLED
                || group == com.shifa.oms.order.OrderStatusGroup.REJECTED;
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
