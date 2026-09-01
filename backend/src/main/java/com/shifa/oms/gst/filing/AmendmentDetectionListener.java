package com.shifa.oms.gst.filing;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Triggers post-filing GSTR-1 amendment detection out-of-band whenever an order (and, by extension,
 * its returns/refunds) changes, by hooking the existing transactional outbox (GST returns &amp;
 * filing, Reqs 3.1–3.8).
 *
 * <p>This mirrors the ledger auto-posting pattern: it is an {@link OutboxEventSink}, so the
 * {@link com.shifa.oms.platform.outbox.OutboxEventPublisher} notifies it for every event a source
 * module publishes and <strong>isolates it in a try/catch</strong> — a detection failure can never
 * break or roll back the originating order/return/refund mutation (Req 3 decoupling). Detection is
 * therefore fully decoupled from the source change while still committing atomically with it, exactly
 * like {@link com.shifa.oms.adminnotification.AdminNotificationOutboxSink}.
 *
 * <p><strong>Wiring.</strong> The system does not (yet) emit a dedicated "document changed" event, so
 * this sink subscribes to the closest existing order-scoped change signals:
 * <ul>
 *   <li>{@link OutboxEvent#EVENT_ORDER_STATUS_CHANGED} — an order's lifecycle status changed
 *       (including transitions that drive returns/refunds); its aggregate is the order id.</li>
 *   <li>{@link OutboxEvent#EVENT_LEDGER_POST} with {@code sourceType == "ORDER"} — a sales invoice was
 *       recorded/auto-posted; its payload carries {@code sourceId} (the order id).</li>
 * </ul>
 * From the resolved order it derives the change's {@code docDate} (the order's creation date, whose
 * calendar month is the potentially-affected Filed_Period) and {@code docRef} (the order code) and
 * delegates to {@link AmendmentService#onDocumentChanged(LocalDate, String)}. Should the platform
 * later add an explicit return/refund change event, this sink can subscribe to it the same way
 * without touching {@link AmendmentService}, which stays independently testable on {@code (docDate,
 * docRef)}.
 */
@Component
public class AmendmentDetectionListener implements OutboxEventSink {

    private static final Logger log = LoggerFactory.getLogger(AmendmentDetectionListener.class);

    /** The source-document type (on a {@code LEDGER_POST} payload) that denotes a sales order. */
    private static final String SOURCE_TYPE_ORDER = "ORDER";

    /** Order-scoped change events that may alter a filed period's GSTR-1 figures. */
    private static final Set<String> HANDLED_TYPES = Set.of(
            OutboxEvent.EVENT_ORDER_STATUS_CHANGED,
            OutboxEvent.EVENT_LEDGER_POST);

    private final AmendmentService amendmentService;
    private final OrderRepository orderRepository;

    public AmendmentDetectionListener(AmendmentService amendmentService,
                                      OrderRepository orderRepository) {
        this.amendmentService = amendmentService;
        this.orderRepository = orderRepository;
    }

    @Override
    public void onEventPublished(OutboxEvent event) {
        String type = event.getEventType();
        if (type == null || !HANDLED_TYPES.contains(type)) {
            return;
        }
        try {
            Long orderId = resolveOrderId(event);
            if (orderId == null) {
                return;
            }
            OrderEntity order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getOrderCode() == null || order.getCreatedAt() == null) {
                return;
            }
            LocalDate docDate = order.getCreatedAt().toLocalDate();
            amendmentService.onDocumentChanged(docDate, order.getOrderCode());
        } catch (RuntimeException e) {
            // Best-effort: never let amendment detection break the originating mutation.
            log.warn("Amendment detection failed for outbox event {} ({}): {}",
                    event.getId(), type, e.getMessage());
        }
    }

    /**
     * The order id an event refers to: the aggregate id for an order-scoped event, or the
     * {@code sourceId} on a {@code LEDGER_POST} payload whose {@code sourceType} is {@code ORDER}.
     * Returns {@code null} for events that are not order-scoped (e.g. a purchase/expense ledger post).
     */
    private Long resolveOrderId(OutboxEvent event) {
        if (OutboxEvent.EVENT_LEDGER_POST.equals(event.getEventType())) {
            Map<String, Object> payload = event.getPayload();
            if (payload == null || !SOURCE_TYPE_ORDER.equals(asString(payload.get("sourceType")))) {
                return null;
            }
            return asLong(payload.get("sourceId"));
        }
        if (OutboxEvent.AGGREGATE_ORDER.equals(event.getAggregateType())) {
            return event.getAggregateId();
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
