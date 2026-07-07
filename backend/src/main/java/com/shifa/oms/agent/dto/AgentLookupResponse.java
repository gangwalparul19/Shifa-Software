package com.shifa.oms.agent.dto;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of a live-agent order lookup (Req 15.1, 15.2).
 *
 * <p>When {@code found} is {@code true}, the tracking fields describe the matched
 * order's current status and courier details. When {@code found} is
 * {@code false}, only {@code message} is meaningful and states that no matching
 * order was found (Req 15.2).
 *
 * @param found         whether a matching order was found
 * @param message       a human-readable message (e.g. the no-match message)
 * @param orderCode     the matched order's code, or {@code null}
 * @param orderStatus   the current lifecycle status, or {@code null}
 * @param awb           the AWB once assigned, or {@code null}
 * @param courierName   the courier company name, or {@code null}
 * @param trackingUrl   the courier tracking link, or {@code null}
 * @param estimatedDelivery the estimated delivery date, or {@code null}
 * @param codAmount     the COD amount to collect, or {@code null}
 * @param paymentStatus the payment classification, or {@code null}
 */
public record AgentLookupResponse(
        boolean found,
        String message,
        String orderCode,
        OrderStatus orderStatus,
        String awb,
        String courierName,
        String trackingUrl,
        LocalDate estimatedDelivery,
        BigDecimal codAmount,
        PaymentStatus paymentStatus) {

    /** A no-match result carrying the standard message (Req 15.2). */
    public static AgentLookupResponse notFound(String message) {
        return new AgentLookupResponse(false, message, null, null, null, null, null, null, null, null);
    }
}
