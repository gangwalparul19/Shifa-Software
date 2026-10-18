package com.shifa.oms.notification;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.QuikShipXModels;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Sends the customer a WhatsApp update at a few key shipment milestones for COD
 * orders, keeping them informed from our side (customer shipment notifications).
 *
 * <p>Milestones and how each is detected:
 * <ul>
 *   <li><b>Order placed</b> — fired once at order creation ({@link #orderPlaced}).</li>
 *   <li><b>Picked up</b> — the courier scan "Shipment picked up".</li>
 *   <li><b>On the way</b> — the FIRST "Vehicle Departed" courier scan (there can
 *       be several; we notify only once).</li>
 *   <li><b>Out for delivery</b> — the courier scan "Out for delivery".</li>
 * </ul>
 *
 * <p>The scan-driven milestones are detected from the live QuikShipX scan
 * timeline ({@link #onScans}), which the 5-minute poller re-sees every cycle, so
 * each milestone is de-duplicated per order via {@link OrderMilestoneNotification}
 * (a {@code UNIQUE(order_id, milestone)} marker claimed before enqueuing). All
 * messages go through the existing WhatsApp outbox + drainer (mock today; the
 * real Meta API drops in later) via {@link WhatsAppNotificationPublisher}.
 *
 * <p>Gated to COD orders (COD or Partially_Paid) per the client requirement.
 */
@Service
public class ShipmentMilestoneNotifier {

    private static final Logger log = LoggerFactory.getLogger(ShipmentMilestoneNotifier.class);

    /** Fallback store name when settings carry no legal/brand name. */
    static final String DEFAULT_STORE_NAME = "Shifa Herbal Remedies";

    /** Milestone marker keys persisted in {@code order_milestone_notifications.milestone}. */
    static final String M_ORDER_PLACED = "ORDER_PLACED";
    static final String M_PICKED_UP = "PICKED_UP";
    static final String M_SHIPMENT_DEPARTED = "SHIPMENT_DEPARTED";
    static final String M_OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";

    private final WhatsAppNotificationPublisher notificationPublisher;
    private final OrderMilestoneNotificationRepository markerRepository;
    private final SettingsService settingsService;

    public ShipmentMilestoneNotifier(WhatsAppNotificationPublisher notificationPublisher,
                                     OrderMilestoneNotificationRepository markerRepository,
                                     SettingsService settingsService) {
        this.notificationPublisher = notificationPublisher;
        this.markerRepository = markerRepository;
        this.settingsService = settingsService;
    }

    /**
     * Notifies the customer that their (COD) order has been placed. Called once
     * from the order-creation transaction; the {@code ORDER_PLACED} marker makes
     * it idempotent even if creation is retried.
     */
    @Transactional
    public void orderPlaced(OrderEntity order) {
        if (order == null || !isCod(order)) {
            return;
        }
        fireOnce(order, M_ORDER_PLACED, NotificationEvent.ORDER_CONFIRMED);
    }

    /**
     * Inspects the latest courier scan timeline for a COD order and fires any
     * not-yet-sent shipment milestone (picked up, first vehicle-departed, out for
     * delivery). Safe to call on every poll cycle: already-sent milestones are
     * skipped via the per-order markers. Never throws into the caller — a
     * notification hiccup must not break courier tracking.
     *
     * @param order the order (must be a persisted COD order to notify)
     * @param scans the courier scan timeline just fetched (any order; may be empty)
     */
    @Transactional
    public void onScans(OrderEntity order, List<QuikShipXModels.Scan> scans) {
        if (order == null || order.getId() == null || scans == null || scans.isEmpty() || !isCod(order)) {
            return;
        }
        try {
            if (hasScan(scans, "picked up")) {
                fireOnce(order, M_PICKED_UP, NotificationEvent.PICKED_UP);
            }
            if (hasScan(scans, "vehicle departed")) {
                fireOnce(order, M_SHIPMENT_DEPARTED, NotificationEvent.SHIPMENT_DEPARTED);
            }
            if (hasScan(scans, "out for delivery")) {
                fireOnce(order, M_OUT_FOR_DELIVERY, NotificationEvent.OUT_FOR_DELIVERY);
            }
        } catch (RuntimeException e) {
            log.warn("Shipment milestone notification failed for order {}: {}",
                    order.getOrderCode(), e.getMessage());
        }
    }

    /**
     * Claims a milestone for an order and enqueues its WhatsApp message exactly
     * once. The claim is the insert of a {@link OrderMilestoneNotification}; the
     * {@code UNIQUE(order_id, milestone)} constraint makes a repeat/concurrent
     * claim fail, in which case we skip (already notified).
     */
    private void fireOnce(OrderEntity order, String milestone, NotificationEvent event) {
        if (markerRepository.existsByOrderIdAndMilestone(order.getId(), milestone)) {
            return;
        }
        try {
            markerRepository.saveAndFlush(new OrderMilestoneNotification(order.getId(), milestone));
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // A concurrent poll claimed it first — do not send twice.
            return;
        }
        notificationPublisher.enqueue(order.getId(), event, contextFor(order), order.getCreatedBy());
        log.debug("Enqueued {} WhatsApp milestone for order {}", milestone, order.getOrderCode());
    }

    /** Whether any scan's instruction OR status contains the given phrase (case-insensitive). */
    private static boolean hasScan(List<QuikShipXModels.Scan> scans, String phrase) {
        String needle = phrase.toLowerCase(Locale.ROOT);
        for (QuikShipXModels.Scan scan : scans) {
            if (contains(scan.instructions(), needle) || contains(scan.status(), needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** A COD order is one payable (wholly or partly) on delivery. */
    private static boolean isCod(OrderEntity order) {
        PaymentStatus status = order.getPaymentStatus();
        return status == PaymentStatus.COD || status == PaymentStatus.PARTIALLY_PAID;
    }

    /** Builds the customer WhatsApp context for a milestone message. */
    private NotificationContext contextFor(OrderEntity order) {
        return new NotificationContext(
                order.getOrderCode(),
                order.getCustomerMobile(),
                null, null, null, null,
                order.getPaymentStatus(),
                order.getCodAmount(),
                order.getCustomerName(),
                resolveStoreName());
    }

    /** The public store/brand name from settings, falling back to the default when unset. */
    private String resolveStoreName() {
        try {
            AppSettings settings = settingsService.getSettings();
            String legalName = settings == null ? null : settings.getLegalName();
            if (legalName != null && !legalName.isBlank()) {
                return legalName.trim();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the default.
        }
        return DEFAULT_STORE_NAME;
    }
}
