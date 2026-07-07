package com.shifa.oms.notification;

import com.shifa.oms.mail.MailMessage;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Enqueues an {@code EMAIL_NOTIFY} outbox event for a customer milestone email
 * (Req 7.2, 10.7, 11.4, 14.1), mirroring {@link WhatsAppNotificationPublisher}.
 *
 * <p>Called from inside the status-change transaction (via the workflow
 * notification fan-out), so the enqueued event commits atomically with the
 * status change. It resolves a plain-text milestone email up front and stores
 * the fully-resolved recipient/subject/body on the event payload, so the
 * {@code EmailOutboxDrainer} can send without re-loading the order aggregate.
 *
 * <p>Customer email is only sent for the three key milestones — {@code APPROVED},
 * {@code DISPATCHED}, {@code DELIVERED} (Req 13.5, 13.6); the
 * {@code NotificationMatrix} is the single source that decides when this is
 * called. When the order has no {@code customer_email} the send is skipped and
 * recorded (returns {@link Optional#empty()}), mirroring the WhatsApp no-mobile
 * skip (Req 7.5).
 */
@Service
public class MailNotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationPublisher.class);

    private final OutboxEventPublisher outboxEventPublisher;

    public MailNotificationPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Resolves and enqueues the milestone email for an order event.
     *
     * @param orderId           the order id
     * @param orderCode         the order code (used in subject/body)
     * @param event             the milestone lifecycle event
     * @param customerEmail     the customer email (skip when blank)
     * @param customerName      the customer name (greeting)
     * @param salespersonUserId the creating salesperson's user id (nullable)
     * @return the resolved message that was enqueued, or empty when skipped
     */
    public Optional<MailMessage> enqueue(Long orderId, String orderCode, NotificationEvent event,
                                         String customerEmail, String customerName,
                                         Long salespersonUserId) {
        if (customerEmail == null || customerEmail.isBlank()) {
            // Missing contact info: skip only this channel and record the skip (Req 7.5).
            log.debug("Skipping milestone email for order {} ({}): no customer email",
                    orderCode, event);
            return Optional.empty();
        }
        MailMessage message = resolve(orderCode, event, customerEmail, customerName);
        outboxEventPublisher.publishEmailNotify(orderId, orderCode, event.name(),
                message.to(), message.subject(), message.body(), salespersonUserId);
        return Optional.of(message);
    }

    /** Builds the plain-text milestone email content. */
    private MailMessage resolve(String orderCode, NotificationEvent event,
                                String to, String customerName) {
        String greeting = (customerName == null || customerName.isBlank())
                ? "Hello," : "Hello " + customerName + ",";
        String subject;
        String line;
        switch (event) {
            case APPROVED -> {
                subject = "Your order " + orderCode + " is confirmed";
                line = "Good news — your order " + orderCode
                        + " has been approved and is being prepared.";
            }
            case DISPATCHED -> {
                subject = "Your order " + orderCode + " has been dispatched";
                line = "Your order " + orderCode
                        + " has been dispatched and is on its way. You will receive tracking"
                        + " updates on WhatsApp.";
            }
            case DELIVERED -> {
                subject = "Your order " + orderCode + " has been delivered";
                line = "Your order " + orderCode
                        + " has been delivered. Thank you for shopping with us.";
            }
            default -> {
                // Defensive: the matrix only routes email for the milestones above.
                subject = "Update on your order " + orderCode;
                line = "There is an update on your order " + orderCode + ".";
            }
        }
        String body = greeting + "\n\n" + line
                + "\n\nWarm regards,\nShifa Herbal Remedies";
        return MailMessage.text(to, subject, body);
    }

    /** Reconstructs a {@link MailMessage} from a stored {@code EMAIL_NOTIFY} payload. */
    public static MailMessage messageFromPayload(java.util.Map<String, Object> payload) {
        return MailMessage.text(
                asString(payload.get("recipientEmail")),
                asString(payload.get("subject")),
                asString(payload.get("body")));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** The outbox event type this publisher writes. */
    public static String eventType() {
        return OutboxEvent.EVENT_EMAIL_NOTIFY;
    }
}
