package com.shifa.oms.notification;

import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The order/tracking facts a {@link WhatsAppMessageFactory} needs to resolve a
 * notification's template parameters (Req 14.1, 14.2).
 *
 * <p>Deliberately a plain data carrier of primitives/enums (no JPA entities), so
 * message assembly is pure and property-testable, and so the notification module
 * does not depend on the courier module. The COD fields drive the
 * COD-iff-COD/Partially_Paid rule on dispatch.
 *
 * @param orderCode          the order identifier (used as {@code order_id})
 * @param customerMobile     the recipient mobile
 * @param courierName        the courier company name (dispatch)
 * @param awb                the AWB (dispatch)
 * @param trackingUrl        the courier tracking link (dispatch)
 * @param estimatedDelivery  the estimated delivery date (dispatch)
 * @param paymentStatus      the order payment classification (drives COD inclusion)
 * @param codAmount          the COD amount to collect
 * @param customerName       the customer's name (order-confirmation greeting)
 * @param storeName          the store/brand name (order-confirmation message)
 */
public record NotificationContext(
        String orderCode,
        String customerMobile,
        String courierName,
        String awb,
        String trackingUrl,
        LocalDate estimatedDelivery,
        PaymentStatus paymentStatus,
        BigDecimal codAmount,
        String customerName,
        String storeName) {

    /**
     * Backward-compatible constructor for the courier-driven lifecycle events
     * (Dispatched / status updates), which do not carry the confirmation-only
     * {@code customerName}/{@code storeName}; delegates with {@code null} for both.
     */
    public NotificationContext(String orderCode, String customerMobile, String courierName,
                               String awb, String trackingUrl, LocalDate estimatedDelivery,
                               PaymentStatus paymentStatus, BigDecimal codAmount) {
        this(orderCode, customerMobile, courierName, awb, trackingUrl, estimatedDelivery,
                paymentStatus, codAmount, null, null);
    }

    /** Whether the COD amount should be shown to the customer (COD or Partially_Paid). */
    public boolean isCodApplicable() {
        return paymentStatus == PaymentStatus.COD || paymentStatus == PaymentStatus.PARTIALLY_PAID;
    }
}
