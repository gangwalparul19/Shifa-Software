package com.shifa.oms.insights.domain;

import java.math.BigDecimal;

/**
 * The risk features of a single open (non-terminal, non-delivered) order
 * (design &sect;Pure domain, &sect;Scoring detail; Req 5.1–5.3), the input to the
 * RTO / delivery-failure risk score. The caller only passes open orders; the
 * engine scores whatever it is handed.
 *
 * @param orderId               the order id ({@code scopeRefId} of the insight)
 * @param orderCode             the human order code, for the insight label
 * @param codAmount             the COD amount to collect ({@code null} for prepaid)
 * @param state                 the destination state, for the insight detail
 * @param priorFailedForCustomer prior failed delivery attempts on this customer
 * @param stateFailureRate      historical delivery-failure rate for the state, in {@code [0, 1]}
 */
public record OpenOrderRisk(
        Long orderId,
        String orderCode,
        BigDecimal codAmount,
        String state,
        int priorFailedForCustomer,
        double stateFailureRate) {
}
