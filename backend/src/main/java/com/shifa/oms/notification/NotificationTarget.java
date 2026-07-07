package com.shifa.oms.notification;

import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;

/**
 * The order facts the {@link NotificationDispatcher} needs to enqueue a matrix
 * event's notifications, without depending on the JPA {@code OrderEntity}
 * (design §5.2). A plain data carrier assembled by
 * {@link com.shifa.oms.order.OrderWorkflowService} from the order aggregate.
 *
 * @param orderId           the order id
 * @param orderCode         the order code (display / template param)
 * @param customerMobile    the customer mobile ({@code null}/blank → WhatsApp skipped)
 * @param customerEmail     the customer email ({@code null}/blank → email skipped)
 * @param customerName      the customer name (greeting)
 * @param salespersonUserId the creating salesperson's user id ({@code createdBy}; may be null)
 * @param paymentStatus     the payment classification (drives COD inclusion on dispatch)
 * @param codAmount         the COD amount
 */
public record NotificationTarget(
        Long orderId,
        String orderCode,
        String customerMobile,
        String customerEmail,
        String customerName,
        Long salespersonUserId,
        PaymentStatus paymentStatus,
        BigDecimal codAmount) {

    /** Whether a usable customer mobile is present (WhatsApp channel). */
    public boolean hasMobile() {
        return customerMobile != null && !customerMobile.isBlank();
    }

    /** Whether a usable customer email is present (email channel). */
    public boolean hasEmail() {
        return customerEmail != null && !customerEmail.isBlank();
    }
}
