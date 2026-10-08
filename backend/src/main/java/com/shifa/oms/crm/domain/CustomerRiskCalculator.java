package com.shifa.oms.crm.domain;

/**
 * Pure, side-effect-free calculator for a customer's delivery-reliability risk
 * (FEATURE-ROADMAP §1.2). Kept free of Spring/JPA so it is trivially unit
 * testable and reused by both the customer-profile read and the order-entry
 * risk nudge.
 *
 * <p>Risk is a function of two counts derived from the customer's order history:
 * how many deliveries <em>failed</em> (customer rejected, RTO, delivery failed,
 * courier lost) versus how many <em>succeeded</em> (delivered / COD collected /
 * closed). Orders still in flight, and cancelled/rejected-at-approval orders,
 * are not "concluded deliveries" and do not count either way.
 *
 * <p>Bands:
 * <ul>
 *   <li>{@link CustomerRiskLevel#HIGH} — at least {@value #HIGH_MIN_FAILURES}
 *       failed deliveries AND a failure rate of at least
 *       {@value #HIGH_MIN_RATE};</li>
 *   <li>{@link CustomerRiskLevel#MEDIUM} — at least one failed delivery (but not
 *       high);</li>
 *   <li>{@link CustomerRiskLevel#LOW} — no failed deliveries.</li>
 * </ul>
 */
public final class CustomerRiskCalculator {

    /** Minimum failed deliveries before a customer can be flagged HIGH risk. */
    public static final long HIGH_MIN_FAILURES = 2;

    /** Minimum failure rate (0..1) before a customer can be flagged HIGH risk. */
    public static final double HIGH_MIN_RATE = 0.4;

    private CustomerRiskCalculator() {
    }

    /**
     * The risk band for the given concluded-delivery counts.
     *
     * @param failedDeliveryCount count of failed deliveries (rejected/RTO/failed/lost)
     * @param deliveredCount      count of successful deliveries (delivered/COD/closed)
     */
    public static CustomerRiskLevel assess(long failedDeliveryCount, long deliveredCount) {
        double rate = failureRate(failedDeliveryCount, deliveredCount);
        if (failedDeliveryCount >= HIGH_MIN_FAILURES && rate >= HIGH_MIN_RATE) {
            return CustomerRiskLevel.HIGH;
        }
        if (failedDeliveryCount >= 1) {
            return CustomerRiskLevel.MEDIUM;
        }
        return CustomerRiskLevel.LOW;
    }

    /**
     * The failure rate as a fraction of concluded deliveries, or {@code 0.0} when
     * the customer has no concluded deliveries yet (no history to judge on).
     */
    public static double failureRate(long failedDeliveryCount, long deliveredCount) {
        long concluded = failedDeliveryCount + deliveredCount;
        if (concluded <= 0) {
            return 0.0;
        }
        return (double) failedDeliveryCount / (double) concluded;
    }
}
