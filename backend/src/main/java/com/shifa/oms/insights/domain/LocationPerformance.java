package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * Per-state sales + delivery outcome over the insight window (design &sect;Pure
 * domain), the input to the location insights ({@code TOP_SALES_LOCATION} and
 * {@code UNDERPERFORMING_LOCATION}).
 *
 * <p>The business has no marketing-spend-by-location data, so a location's
 * marketing worth is inferred purely from order outcomes: a state with strong
 * revenue is where marketing is paying off (push harder), while a state with
 * meaningful order volume but a high delivery-failure/RTO share is one where
 * spend is NOT converting into delivered sales (fix delivery or cut spend).
 *
 * @param state     the destination state (blank/unknown states are grouped as "Unknown")
 * @param orders    orders placed to this state in the window (any status)
 * @param revenue   window revenue to this state, excluding rejected/cancelled
 * @param delivered orders to this state that reached a successful terminal status
 * @param failed    orders to this state that reached a failed terminal status (RTO / failed / rejected / redispatch)
 */
public record LocationPerformance(
        String state,
        long orders,
        BigDecimal revenue,
        long delivered,
        long failed) {
}
