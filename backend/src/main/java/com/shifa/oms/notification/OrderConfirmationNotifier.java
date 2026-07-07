package com.shifa.oms.notification;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import org.springframework.stereotype.Service;

/**
 * Enqueues the "order confirmation" WhatsApp notification for a freshly placed
 * storefront order (ROADMAP 1.2). Unlike the courier-driven lifecycle
 * notifications, this fires once, right when the customer places the order, to
 * acknowledge it immediately.
 *
 * <p>It resolves the pre-approved {@code order_confirmed} template and its
 * parameters (customer name, order code, store name) via
 * {@link WhatsAppNotificationPublisher}, which writes a {@code WHATSAPP_NOTIFY}
 * outbox event. Because it is called from inside the order-creation transaction,
 * the event commits atomically with the order and is delivered out-of-band by
 * the WhatsApp drainer (async + retryable) — the request path never calls the
 * WhatsApp client synchronously.
 */
@Service
public class OrderConfirmationNotifier {

    /** Fallback store name when settings carry no legal/brand name (mirrors the storefront config). */
    static final String DEFAULT_STORE_NAME = "Shifa Herbal Remedies";

    private final WhatsAppNotificationPublisher notificationPublisher;
    private final SettingsService settingsService;

    public OrderConfirmationNotifier(WhatsAppNotificationPublisher notificationPublisher,
                                     SettingsService settingsService) {
        this.notificationPublisher = notificationPublisher;
        this.settingsService = settingsService;
    }

    /**
     * Enqueues exactly one order-confirmation notification for the placed order.
     * The order's mobile is the recipient; the customer name and store name
     * personalise the pre-approved template.
     *
     * @param order the just-created order aggregate (must have an id/code)
     * @return the resolved message that was enqueued
     */
    public WhatsAppMessage notifyOrderPlaced(OrderEntity order) {
        NotificationContext context = new NotificationContext(
                order.getOrderCode(),
                order.getCustomerMobile(),
                null,
                null,
                null,
                null,
                order.getPaymentStatus(),
                order.getCodAmount(),
                order.getCustomerName(),
                resolveStoreName());
        return notificationPublisher.enqueue(order.getId(), NotificationEvent.ORDER_CONFIRMED, context);
    }

    /** The public store/brand name, falling back to the default when unset. */
    private String resolveStoreName() {
        AppSettings settings = settingsService.getSettings();
        String legalName = settings == null ? null : settings.getLegalName();
        return (legalName == null || legalName.isBlank()) ? DEFAULT_STORE_NAME : legalName;
    }
}
