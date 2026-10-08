package com.shifa.oms.gst.dto;

import com.shifa.oms.gst.domain.SupplyType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One order behind a GST summary figure (CA GST dashboard drill-down): lets the
 * CA click a rate / HSN / state row and see the contributing orders and how much
 * is still to be collected (Req 6 — track every in/out).
 */
public record GstOrderRow(
        Long orderId,
        String orderCode,
        LocalDate orderDate,
        String customerName,
        String customerMobile,
        String state,
        SupplyType supplyType,
        BigDecimal taxable,
        BigDecimal tax,
        BigDecimal total,
        BigDecimal received,
        BigDecimal remaining,
        /** Non-COD balance the customer still owes directly (e.g. a partial prepaid order). */
        BigDecimal customerRemaining,
        /** COD amount still to be collected/remitted by the courier (0 once settled). */
        BigDecimal codPending,
        String paymentStatus,
        String orderStatus
) {
}
