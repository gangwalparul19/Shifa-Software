package com.shifa.oms.order;

/**
 * The categorized reason an order was marked RTO (returned to origin) — label
 * redesign feature. Required when a packer/admin manually marks an order RTO by
 * scanning its label; persisted on {@code orders.rto_reason} as
 * {@code VARCHAR(30)} via {@code @Enumerated(EnumType.STRING)}. Mirrors the
 * {@code lead.LostReason} categorized-reason pattern.
 *
 * <ul>
 *   <li>{@link #CUSTOMER_UNAVAILABLE} — the customer could not be reached/was
 *       not present at delivery.</li>
 *   <li>{@link #CUSTOMER_REFUSED} — the customer declined to accept the parcel.</li>
 *   <li>{@link #ADDRESS_ISSUE} — the delivery address was incorrect/incomplete/
 *       unreachable.</li>
 *   <li>{@link #DAMAGED_IN_TRANSIT} — the parcel was damaged before delivery.</li>
 *   <li>{@link #OTHER} — anything else; accepts an optional free-text note
 *       ({@code rto_reason_note}).</li>
 * </ul>
 */
public enum RtoReason {
    CUSTOMER_UNAVAILABLE,
    CUSTOMER_REFUSED,
    ADDRESS_ISSUE,
    DAMAGED_IN_TRANSIT,
    OTHER
}
