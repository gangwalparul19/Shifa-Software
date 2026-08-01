package com.shifa.oms.notification;

import com.shifa.oms.auth.Role;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.shifa.oms.notification.NotificationChannel.EMAIL;
import static com.shifa.oms.notification.NotificationChannel.IN_APP;
import static com.shifa.oms.notification.NotificationChannel.WHATSAPP;

/**
 * The single source of truth for lifecycle notifications (design §5.1, Req 13.1,
 * 13.2, 13.7): a pure, table-driven lookup from a lifecycle event (the
 * {@link OrderStatus} just entered) to the exact set of
 * {@link NotificationSpec (channel, recipient)} notifications to enqueue.
 *
 * <p>{@link com.shifa.oms.order.OrderWorkflowService} consults this after every
 * successful transition and enqueues <em>exactly</em> {@link #specsFor(OrderStatus)}
 * — no more, no less (Property 16). The table below reproduces §5.1 verbatim:
 *
 * <pre>
 * Event               WhatsApp  Email      In-app (staff)
 * APPROVED            customer  customer*  salesperson-creator
 * REJECTED            —         —          salesperson-creator
 * PACKED              customer  —          salesperson-creator + ADMIN
 * HANDED_TO_DELIVERY  —         —          ADMIN
 * DISPATCHED          customer  customer*  ADMIN + salesperson-creator + PACKING_USER
 * OUT_FOR_DELIVERY    customer  —          —
 * DELIVERED           customer  customer*  ADMIN + salesperson-creator
 * CUSTOMER_REJECTED   —         —          ADMIN + salesperson-creator + ACCOUNTANT
 * DELIVERY_FAILED     —         —          ADMIN + PACKING_USER
 * CANCELLED           —         —          ADMIN + salesperson-creator
 * RTO                 customer  —          ADMIN
 * REDISPATCH        customer  —          ADMIN
 * </pre>
 *
 * <p>* Email is present <b>iff</b> the event is a key milestone — exactly
 * {@code APPROVED}, {@code DISPATCHED}, {@code DELIVERED} (Req 13.5, 13.6,
 * Property 17). This is a single rule encoded in {@link #KEY_MILESTONES}, not
 * scattered per event, so the table and the rule cannot drift apart.
 *
 * <p>Pure and immutable: no Spring, DB, or I/O — it only returns the specification.
 */
@Component
public class NotificationMatrix {

    /**
     * The events on which a customer milestone email is enqueued (Req 13.5, 13.6).
     * Any {@code EMAIL}/{@code CUSTOMER} spec appears iff the event is in this set.
     */
    public static final Set<OrderStatus> KEY_MILESTONES =
            Collections.unmodifiableSet(EnumSet.of(
                    OrderStatus.APPROVED, OrderStatus.DISPATCHED, OrderStatus.DELIVERED));

    private static final Map<OrderStatus, Set<NotificationSpec>> MATRIX = buildMatrix();

    private static Map<OrderStatus, Set<NotificationSpec>> buildMatrix() {
        Map<OrderStatus, Set<NotificationSpec>> m = new EnumMap<>(OrderStatus.class);

        // APPROVED (Req 7.1, 7.2, 7.3).
        m.put(OrderStatus.APPROVED, specs(
                customerWhatsApp(),
                customerEmail(),
                inAppCreator()));

        // REJECTED — salesperson-creator only.
        m.put(OrderStatus.REJECTED, specs(
                inAppCreator()));

        // PACKED (Req 8.4, 8.5).
        m.put(OrderStatus.PACKED, specs(
                customerWhatsApp(),
                inAppCreator(),
                inAppRole(Role.ADMIN)));

        // HANDED_TO_DELIVERY (Req 9.7).
        m.put(OrderStatus.HANDED_TO_DELIVERY, specs(
                inAppRole(Role.ADMIN)));

        // DISPATCHED (Req 10.6, 10.7, 10.8).
        m.put(OrderStatus.DISPATCHED, specs(
                customerWhatsApp(),
                customerEmail(),
                inAppRole(Role.ADMIN),
                inAppCreator(),
                inAppRole(Role.PACKING_USER)));

        // OUT_FOR_DELIVERY (Req 11.3).
        m.put(OrderStatus.OUT_FOR_DELIVERY, specs(
                customerWhatsApp()));

        // DELIVERED (Req 11.4).
        m.put(OrderStatus.DELIVERED, specs(
                customerWhatsApp(),
                customerEmail(),
                inAppRole(Role.ADMIN),
                inAppCreator()));

        // CUSTOMER_REJECTED (Req 11.5).
        m.put(OrderStatus.CUSTOMER_REJECTED, specs(
                inAppRole(Role.ADMIN),
                inAppCreator(),
                inAppRole(Role.ACCOUNTANT)));

        // DELIVERY_FAILED (Req 11.6).
        m.put(OrderStatus.DELIVERY_FAILED, specs(
                inAppRole(Role.ADMIN),
                inAppRole(Role.PACKING_USER)));

        // CANCELLED (Req 11.7).
        m.put(OrderStatus.CANCELLED, specs(
                inAppRole(Role.ADMIN),
                inAppCreator()));

        // RTO — existing customer WhatsApp + ADMIN in-app.
        m.put(OrderStatus.RTO, specs(
                customerWhatsApp(),
                inAppRole(Role.ADMIN)));

        // REDISPATCH — existing customer WhatsApp + ADMIN in-app (claim alert).
        m.put(OrderStatus.REDISPATCH, specs(
                customerWhatsApp(),
                inAppRole(Role.ADMIN)));

        // Freeze.
        Map<OrderStatus, Set<NotificationSpec>> frozen = new EnumMap<>(OrderStatus.class);
        for (Map.Entry<OrderStatus, Set<NotificationSpec>> e : m.entrySet()) {
            // Defensive assertion of the milestone-email rule (Property 17): an
            // EMAIL/CUSTOMER spec is present iff the event is a key milestone.
            boolean hasEmail = e.getValue().stream().anyMatch(s ->
                    s.channel() == EMAIL
                            && s.recipient().kind() == NotificationRecipient.Kind.CUSTOMER);
            if (hasEmail != KEY_MILESTONES.contains(e.getKey())) {
                throw new IllegalStateException(
                        "Milestone-email rule violated for " + e.getKey());
            }
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * The exact set of notifications to enqueue for a lifecycle event.
     *
     * @param event the {@link OrderStatus} just entered
     * @return an immutable set of {@link NotificationSpec} (empty when the event
     *         has no notifications, e.g. {@code IN_TRANSIT}, {@code CLOSED},
     *         {@code COD_COLLECTED})
     */
    public Set<NotificationSpec> specsFor(OrderStatus event) {
        if (event == null) {
            return Set.of();
        }
        return MATRIX.getOrDefault(event, Set.of());
    }

    /** Whether a customer milestone email is specified for an event (Property 17). */
    public boolean hasCustomerEmail(OrderStatus event) {
        return event != null && KEY_MILESTONES.contains(event);
    }

    // --- Table helpers ------------------------------------------------------

    private static Set<NotificationSpec> specs(NotificationSpec... specs) {
        return new LinkedHashSet<>(Set.of(specs));
    }

    private static NotificationSpec customerWhatsApp() {
        return NotificationSpec.of(WHATSAPP, NotificationRecipient.customer());
    }

    private static NotificationSpec customerEmail() {
        return NotificationSpec.of(EMAIL, NotificationRecipient.customer());
    }

    private static NotificationSpec inAppRole(Role role) {
        return NotificationSpec.of(IN_APP, NotificationRecipient.role(role));
    }

    private static NotificationSpec inAppCreator() {
        return NotificationSpec.of(IN_APP, NotificationRecipient.salespersonCreator());
    }
}
