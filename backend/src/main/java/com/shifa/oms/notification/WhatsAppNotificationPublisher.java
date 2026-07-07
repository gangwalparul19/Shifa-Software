package com.shifa.oms.notification;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enqueues a {@code WHATSAPP_NOTIFY} outbox event for a customer-facing order
 * status change, so the notification is sent out-of-band by the WhatsApp drainer
 * rather than inline on the courier status path (Req 14.1, 14.2).
 *
 * <p>Called from within the courier status transaction (task 14's applier), so
 * the enqueued event commits atomically with the status change. It resolves the
 * pre-approved template and parameters up front via {@link WhatsAppMessageFactory}
 * and stores the fully-resolved message on the event payload, so the drainer can
 * send without re-loading the order aggregate.
 */
@Service
public class WhatsAppNotificationPublisher {

    private final WhatsAppMessageFactory messageFactory;
    private final OutboxEventPublisher outboxEventPublisher;

    public WhatsAppNotificationPublisher(WhatsAppMessageFactory messageFactory,
                                         OutboxEventPublisher outboxEventPublisher) {
        this.messageFactory = messageFactory;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Resolves and enqueues the WhatsApp notification for an order event.
     *
     * @param orderId the order id
     * @param event   the lifecycle event that fired
     * @param context the order/tracking facts needed to resolve the template
     * @return the resolved message that was enqueued (useful for tests/callers)
     */
    public WhatsAppMessage enqueue(Long orderId, NotificationEvent event, NotificationContext context) {
        return enqueue(orderId, event, context, null);
    }

    /**
     * Overload carrying the creating salesperson's user id on the enqueued
     * {@code WHATSAPP_NOTIFY} payload (from {@code OrderEntity.createdBy}) for
     * traceability/addressing (design §5.2). A {@code null} id is omitted.
     */
    public WhatsAppMessage enqueue(Long orderId, NotificationEvent event,
                                   NotificationContext context, Long salespersonUserId) {
        WhatsAppMessage message = messageFactory.build(event, context);
        outboxEventPublisher.publishWhatsAppNotify(
                orderId,
                context.orderCode(),
                event.name(),
                message.recipientMobile(),
                message.templateName(),
                toParameterMaps(message.parameters()),
                salespersonUserId);
        return message;
    }

    /** Reconstructs a {@link WhatsAppMessage} from a stored {@code WHATSAPP_NOTIFY} payload. */
    public static WhatsAppMessage messageFromPayload(Map<String, Object> payload) {
        String recipient = asString(payload.get("recipientMobile"));
        String templateName = asString(payload.get("templateName"));
        List<WhatsAppMessage.Param> params = new ArrayList<>();
        Object raw = payload.get("parameters");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    params.add(new WhatsAppMessage.Param(
                            asString(entry.get("name")), asString(entry.get("value"))));
                }
            }
        }
        return new WhatsAppMessage(recipient, templateName, params);
    }

    private List<Map<String, String>> toParameterMaps(List<WhatsAppMessage.Param> params) {
        List<Map<String, String>> maps = new ArrayList<>();
        for (WhatsAppMessage.Param param : params) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("name", param.name());
            map.put("value", param.value());
            maps.add(map);
        }
        return maps;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** The outbox event type this publisher writes. */
    public static String eventType() {
        return OutboxEvent.EVENT_WHATSAPP_NOTIFY;
    }
}
