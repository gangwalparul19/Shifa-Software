package com.shifa.oms.crm.domain;

/**
 * A customer's delivery-reliability risk band (FEATURE-ROADMAP §1.2), derived
 * from their history of failed vs successful deliveries.
 *
 * <p>Used to nudge staff toward prepaid collection for risky customers at order
 * entry, and to badge the customer profile. Purely computed from order outcomes
 * — never stored.
 */
public enum CustomerRiskLevel {

    /** No (or negligible) failed deliveries — safe to ship COD. */
    LOW,

    /** At least one failed delivery — worth a second look. */
    MEDIUM,

    /** Repeated failed deliveries at a high failure rate — prefer prepaid. */
    HIGH
}
