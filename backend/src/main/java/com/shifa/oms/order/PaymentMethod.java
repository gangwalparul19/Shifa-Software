package com.shifa.oms.order;

/**
 * How an order-entry payment was collected. This describes the payment
 * transaction, not the derived order-level {@link com.shifa.oms.order.domain.PaymentStatus}.
 */
public enum PaymentMethod {
    UPI_QR,
    BANK_TRANSFER,
    CASH,
    SHOPIFY,
    OTHER;

    /** Parses a client value; null/blank defaults to the normal UPI flow. */
    public static PaymentMethod from(String value) {
        if (value == null || value.isBlank()) {
            return UPI_QR;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new com.shifa.oms.common.ValidationException(
                    "paymentMethod must be UPI_QR, BANK_TRANSFER, or CASH.");
        }
    }

    /** Whether this method must name a configured receiving account and UTR. */
    public boolean requiresReceivingAccount() {
        return this == UPI_QR || this == BANK_TRANSFER;
    }
}
