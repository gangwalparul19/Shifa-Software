package com.shifa.oms.notification;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Enqueues, for a lifecycle event, <em>exactly</em> the set of notifications the
 * {@link NotificationMatrix} specifies (design §5.2, Property 16). This is the
 * single fan-out point the {@link com.shifa.oms.order.OrderWorkflowService}
 * calls after each successful transition, so the matrix is the one source of
 * truth for who is notified and how (Req 13.2).
 *
 * <p>Per channel:
 * <ul>
 *   <li><b>WhatsApp (customer)</b> → {@link WhatsAppNotificationPublisher}; skipped
 *       and recorded when the order has no mobile (Req 7.5, Property 18);</li>
 *   <li><b>Email (customer)</b> → {@link MailNotificationPublisher}; skipped and
 *       recorded when the order has no email (Req 7.5, Property 18);</li>
 *   <li><b>In-app (staff)</b> → {@link StaffNotificationDispatcher}, addressed to a
 *       role (all active users) or the creating salesperson (Req 13.4, 7.3).</li>
 * </ul>
 *
 * <p>Every enqueue happens inside the caller's status-change transaction, so the
 * notifications commit atomically with the status change (Req 14.1). The returned
 * {@link Result} records exactly which specs were enqueued and which were skipped,
 * so callers/tests can assert the enqueued set equals the matrix.
 */
@Service
public class NotificationDispatcher {

    private final NotificationMatrix matrix;
    private final WhatsAppNotificationPublisher whatsAppPublisher;
    private final MailNotificationPublisher mailPublisher;
    private final StaffNotificationDispatcher staffDispatcher;

    public NotificationDispatcher(NotificationMatrix matrix,
                                  WhatsAppNotificationPublisher whatsAppPublisher,
                                  MailNotificationPublisher mailPublisher,
                                  StaffNotificationDispatcher staffDispatcher) {
        this.matrix = matrix;
        this.whatsAppPublisher = whatsAppPublisher;
        this.mailPublisher = mailPublisher;
        this.staffDispatcher = staffDispatcher;
    }

    /**
     * The outcome of a dispatch: the specs actually enqueued and those skipped
     * (missing customer contact, or an unaddressable salesperson creator).
     */
    public record Result(Set<NotificationSpec> enqueued, Set<NotificationSpec> skipped) {
    }

    /**
     * Enqueues the matrix notifications for {@code event}.
     *
     * @param target           the order facts
     * @param event            the {@link OrderStatus} just entered
     * @param whatsAppOverride an optional pre-built WhatsApp context carrying the
     *                         courier tracking details for {@code DISPATCHED}; when
     *                         null a basic context is built from {@code target}
     * @return the enqueued / skipped breakdown
     */
    public Result dispatch(NotificationTarget target, OrderStatus event,
                           NotificationContext whatsAppOverride) {
        Set<NotificationSpec> enqueued = new LinkedHashSet<>();
        Set<NotificationSpec> skipped = new LinkedHashSet<>();

        for (NotificationSpec spec : matrix.specsFor(event)) {
            switch (spec.channel()) {
                case WHATSAPP -> dispatchWhatsApp(target, event, whatsAppOverride, spec, enqueued, skipped);
                case EMAIL -> dispatchEmail(target, event, spec, enqueued, skipped);
                case IN_APP -> dispatchInApp(target, event, spec, enqueued, skipped);
            }
        }
        return new Result(enqueued, skipped);
    }

    private void dispatchWhatsApp(NotificationTarget target, OrderStatus event,
                                  NotificationContext override, NotificationSpec spec,
                                  Set<NotificationSpec> enqueued, Set<NotificationSpec> skipped) {
        Optional<NotificationEvent> customerEvent = NotificationEvent.fromOrderStatus(event);
        if (!target.hasMobile() || customerEvent.isEmpty()) {
            skipped.add(spec);
            return;
        }
        NotificationContext context = override != null ? override : basicContext(target);
        whatsAppPublisher.enqueue(target.orderId(), customerEvent.get(), context,
                target.salespersonUserId());
        enqueued.add(spec);
    }

    private void dispatchEmail(NotificationTarget target, OrderStatus event,
                               NotificationSpec spec,
                               Set<NotificationSpec> enqueued, Set<NotificationSpec> skipped) {
        Optional<NotificationEvent> customerEvent = NotificationEvent.fromOrderStatus(event);
        if (customerEvent.isEmpty()) {
            skipped.add(spec);
            return;
        }
        Optional<?> sent = mailPublisher.enqueue(target.orderId(), target.orderCode(),
                customerEvent.get(), target.customerEmail(), target.customerName(),
                target.salespersonUserId());
        if (sent.isPresent()) {
            enqueued.add(spec);
        } else {
            skipped.add(spec);
        }
    }

    private void dispatchInApp(NotificationTarget target, OrderStatus event,
                               NotificationSpec spec,
                               Set<NotificationSpec> enqueued, Set<NotificationSpec> skipped) {
        String type = "ORDER_" + event.name();
        String title = titleFor(event, target.orderCode());
        String detail = null;
        String severity = severityFor(event);
        NotificationRecipient recipient = spec.recipient();
        AdminNotification written;
        if (recipient.kind() == NotificationRecipient.Kind.ROLE) {
            written = staffDispatcher.dispatchToRole(type, title, detail, severity,
                    target.orderId(), target.orderCode(), null, recipient.role());
        } else if (recipient.kind() == NotificationRecipient.Kind.SALESPERSON_CREATOR) {
            // Only addressable when the order has a creating salesperson (createdBy).
            written = staffDispatcher.dispatchToUser(type, title, detail, severity,
                    target.orderId(), target.orderCode(), null, target.salespersonUserId());
        } else {
            written = null;
        }
        // Inline workflow path uses a null source-event id, so a row is always
        // written unless the recipient is unaddressable (e.g. no salesperson creator).
        if (written != null) {
            enqueued.add(spec);
        } else {
            skipped.add(spec);
        }
    }

    private NotificationContext basicContext(NotificationTarget target) {
        return new NotificationContext(
                target.orderCode(), target.customerMobile(), null, null, null, null,
                target.paymentStatus(), target.codAmount());
    }

    private static String titleFor(OrderStatus event, String orderCode) {
        String suffix = orderCode != null ? " " + orderCode : "";
        return switch (event) {
            case APPROVED -> "Order approved" + suffix;
            case REJECTED -> "Order rejected" + suffix;
            case PACKED -> "Order packed" + suffix;
            case HANDED_TO_DELIVERY -> "Order handed to delivery" + suffix;
            case DISPATCHED -> "Order dispatched" + suffix;
            case OUT_FOR_DELIVERY -> "Order out for delivery" + suffix;
            case DELIVERED -> "Order delivered" + suffix;
            case CUSTOMER_REJECTED -> "Order rejected by customer" + suffix;
            case DELIVERY_FAILED -> "Delivery failed" + suffix;
            case CANCELLED -> "Order cancelled" + suffix;
            case RTO -> "Order returned to origin" + suffix;
            case REDISPATCH -> "Order marked for redispatch" + suffix;
            default -> "Order update" + suffix;
        };
    }

    private static String severityFor(OrderStatus event) {
        return switch (event) {
            case APPROVED, PACKED, DISPATCHED, DELIVERED -> AdminNotification.SEVERITY_SUCCESS;
            case REJECTED, CUSTOMER_REJECTED, DELIVERY_FAILED, RTO, REDISPATCH, CANCELLED ->
                    AdminNotification.SEVERITY_DANGER;
            default -> AdminNotification.SEVERITY_INFO;
        };
    }
}
