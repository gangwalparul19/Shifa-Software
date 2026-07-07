package com.shifa.oms.notification;

import com.shifa.oms.statemachine.OrderStatus;

import java.util.Optional;

/**
 * A customer-facing lifecycle event that triggers a WhatsApp notification
 * (Req 14.1, 14.2).
 *
 * <p>Each value maps to the courier-driven {@link OrderStatus} the customer
 * should be told about:
 * <ul>
 *   <li>{@link #DISPATCHED} — the full tracking payload incl. COD when
 *       applicable (Req 14.1);</li>
 *   <li>{@link #OUT_FOR_DELIVERY}, {@link #DELIVERED}, {@link #RTO},
 *       {@link #COURIER_LOST} — a status-update message reflecting the new state
 *       (Req 14.2).</li>
 * </ul>
 * {@link #fromOrderStatus(OrderStatus)} returns empty for statuses that do not
 * notify the customer, so callers can enqueue a message only when relevant.
 *
 * <p>{@link #ORDER_CONFIRMED} is not courier-driven: it fires once, right when a
 * storefront order is placed, to acknowledge the order to the customer (ROADMAP
 * 1.2). It is therefore never returned by {@link #fromOrderStatus(OrderStatus)}.
 */
public enum NotificationEvent {

    ORDER_CONFIRMED,
    DISPATCHED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    RTO,
    COURIER_LOST;

    /**
     * The notification event for a courier-driven order status, if that status
     * notifies the customer.
     *
     * @param status the order status just entered
     * @return the matching event, or empty when the status is not customer-facing
     */
    public static Optional<NotificationEvent> fromOrderStatus(OrderStatus status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case DISPATCHED -> Optional.of(DISPATCHED);
            case OUT_FOR_DELIVERY -> Optional.of(OUT_FOR_DELIVERY);
            case DELIVERED -> Optional.of(DELIVERED);
            case RTO -> Optional.of(RTO);
            case COURIER_LOST -> Optional.of(COURIER_LOST);
            default -> Optional.empty();
        };
    }
}
