package com.shifa.oms.adminnotification;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventSink;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Persists a durable {@link AdminNotification} row whenever an admin-facing
 * outbox event is published ("operations depth" Feature 2).
 *
 * <p>This hooks into {@link com.shifa.oms.platform.outbox.OutboxEventPublisher}
 * via the {@link OutboxEventSink} extension point, selecting exactly the same
 * event types the SSE relay surfaces ({@link #ADMIN_NOTIFICATION_TYPES}, mirroring
 * {@code OutboxSseRelay.ADMIN_NOTIFICATION_TYPES}). It writes the notification in
 * the same transaction as the event that produced it (so the record commits
 * atomically with the domain change, exactly like the outbox row), de-duplicated
 * on the outbox event id. The SSE relay continues to consume the same rows
 * independently — the real-time contract is unchanged.
 *
 * <p>Best-effort: the publisher isolates this sink in a try/catch, so a failure
 * to persist a notification never breaks the originating operation.
 */
@Component
public class AdminNotificationOutboxSink implements OutboxEventSink {

    /** The event types surfaced to admins (mirrors {@code OutboxSseRelay.ADMIN_NOTIFICATION_TYPES}). */
    static final Set<String> ADMIN_NOTIFICATION_TYPES = Set.of(
            OutboxEvent.EVENT_ORDER_PACKED,
            OutboxEvent.EVENT_ORDER_STATUS_CHANGED,
            OutboxEvent.EVENT_CLAIM_FILED_REQUIRED,
            OutboxEvent.EVENT_COURIER_ASSIGN_FAILED,
            OutboxEvent.EVENT_WHATSAPP_FAILED,
            OutboxEvent.EVENT_BACKUP_FAILED,
            OutboxEvent.EVENT_LOW_STOCK);

    private final AdminNotificationService notificationService;

    public AdminNotificationOutboxSink(AdminNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onEventPublished(OutboxEvent event) {
        String type = event.getEventType();
        if (type == null || !ADMIN_NOTIFICATION_TYPES.contains(type)) {
            return;
        }
        Map<String, Object> payload = event.getPayload() != null ? event.getPayload() : Map.of();
        String orderCode = asString(payload.get("orderCode"));
        Long orderId = OutboxEvent.AGGREGATE_ORDER.equals(event.getAggregateType())
                ? event.getAggregateId()
                : asLong(payload.get("orderId"));

        notificationService.record(
                type,
                titleFor(type, payload, orderCode),
                detailFor(type, payload),
                severityFor(type),
                orderId,
                orderCode,
                event.getId());
    }

    /** A short, human-readable headline per event type. */
    private static String titleFor(String type, Map<String, Object> payload, String orderCode) {
        return switch (type) {
            case OutboxEvent.EVENT_ORDER_PACKED ->
                    "Order packed" + suffix(orderCode);
            case OutboxEvent.EVENT_ORDER_STATUS_CHANGED ->
                    "Order status changed" + suffix(orderCode);
            case OutboxEvent.EVENT_CLAIM_FILED_REQUIRED ->
                    "Courier claim required" + suffix(orderCode);
            case OutboxEvent.EVENT_COURIER_ASSIGN_FAILED ->
                    "Courier assignment failed" + suffix(orderCode);
            case OutboxEvent.EVENT_WHATSAPP_FAILED ->
                    "WhatsApp notification failed" + suffix(orderCode);
            case OutboxEvent.EVENT_BACKUP_FAILED ->
                    "Backup failed";
            case OutboxEvent.EVENT_LOW_STOCK ->
                    lowStockTitle(payload);
            default -> type;
        };
    }

    private static String lowStockTitle(Map<String, Object> payload) {
        boolean out = Boolean.TRUE.equals(payload.get("outOfStock"));
        String name = asString(payload.getOrDefault("name", payload.get("sku")));
        String label = out ? "Out of stock" : "Low stock";
        return name != null ? label + ": " + name : label;
    }

    /** A longer detail line per event type, built from the event payload. */
    private static String detailFor(String type, Map<String, Object> payload) {
        return switch (type) {
            case OutboxEvent.EVENT_ORDER_PACKED -> compact(
                    "Customer", payload.get("customerName"),
                    "Packed by", payload.get("packedBy"));
            case OutboxEvent.EVENT_ORDER_STATUS_CHANGED -> compact(
                    "New status", payload.get("newStatus"));
            case OutboxEvent.EVENT_CLAIM_FILED_REQUIRED -> compact(
                    "AWB", payload.get("awb"),
                    "Amount", payload.get("amount"));
            case OutboxEvent.EVENT_COURIER_ASSIGN_FAILED -> compact(
                    "Error", payload.get("error"));
            case OutboxEvent.EVENT_WHATSAPP_FAILED -> compact(
                    "Template", payload.get("templateName"),
                    "Error", payload.get("error"));
            case OutboxEvent.EVENT_BACKUP_FAILED -> compact(
                    "Date", payload.get("backupDate"),
                    "Error", payload.get("error"));
            case OutboxEvent.EVENT_LOW_STOCK -> compact(
                    "SKU", payload.get("sku"),
                    "On hand", payload.get("stockQuantity"),
                    "Threshold", payload.get("threshold"));
            default -> null;
        };
    }

    /** Colour hint for the console: failures are danger, low stock a warning, rest info. */
    private static String severityFor(String type) {
        return switch (type) {
            case OutboxEvent.EVENT_COURIER_ASSIGN_FAILED,
                 OutboxEvent.EVENT_WHATSAPP_FAILED,
                 OutboxEvent.EVENT_BACKUP_FAILED,
                 OutboxEvent.EVENT_CLAIM_FILED_REQUIRED -> AdminNotification.SEVERITY_DANGER;
            case OutboxEvent.EVENT_LOW_STOCK -> AdminNotification.SEVERITY_WARNING;
            case OutboxEvent.EVENT_ORDER_PACKED -> AdminNotification.SEVERITY_SUCCESS;
            default -> AdminNotification.SEVERITY_INFO;
        };
    }

    private static String suffix(String orderCode) {
        return orderCode != null ? " " + orderCode : "";
    }

    /** Joins alternating label/value pairs, skipping null values; returns null when empty. */
    private static String compact(Object... labelValuePairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            Object value = labelValuePairs[i + 1];
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(labelValuePairs[i]).append(": ").append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
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
