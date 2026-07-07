package com.shifa.oms.order.domain;

/**
 * Payment classification for an Order (Requirement 7, glossary "Payment_Status").
 *
 * <ul>
 *   <li>{@link #COD} — nothing received at entry; full amount collected on delivery.</li>
 *   <li>{@link #PARTIALLY_PAID} — part received; remainder collected on delivery.</li>
 *   <li>{@link #FULLY_PAID} — full amount received at entry; nothing to collect.</li>
 * </ul>
 */
public enum PaymentStatus {
    COD,
    PARTIALLY_PAID,
    FULLY_PAID
}
