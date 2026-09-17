package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Read-only result of resolving an order-barcode scan on the RTO page (label
 * redesign feature): identifies the order and its current status, and reports
 * whether marking it RTO is currently a legal move — without changing the
 * order. The client must obtain the reason (+ optional note) and explicit
 * confirmation, then invoke {@code POST /api/packing/{id}/rto}; the final
 * request remains the source of truth for authorization and concurrent status
 * changes.
 *
 * <p>{@code scannedViaCourier}/{@code courierName}/{@code courierAwb} report
 * whether the scanned barcode was actually the delivery partner's own barcode
 * (single-barcode label feature) rather than our internal order code, so the
 * RTO page can show the packer "scanned via &lt;partner&gt; — AWB &lt;value&gt;"
 * as requested.
 */
public record RtoScanPreviewResponse(
        String message,
        OrderSummaryResponse order,
        boolean eligible,
        boolean scannedViaCourier,
        String courierName,
        String courierAwb
) {

    /** Statuses from which a manual RTO mark is a legal move (mirrors {@code OrderStatus}'s RTO edges). */
    private static final Set<OrderStatus> RTO_ELIGIBLE_STATUSES = EnumSet.of(
            OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    /** Resolves a preview for a scan matched by our own order code (no courier barcode involved). */
    public static RtoScanPreviewResponse from(OrderEntity order) {
        return from(order, false, null, null);
    }

    /**
     * Resolves a preview reporting whether the barcode scanned was the delivery
     * partner's own barcode (label redesign feature).
     *
     * @param scannedViaCourier whether the match came from a partner barcode (not our order code)
     * @param courierName       the partner's display name, or {@code null} when unknown
     * @param courierAwb        the scanned AWB value, or {@code null} when scanned via our order code
     */
    public static RtoScanPreviewResponse from(OrderEntity order, boolean scannedViaCourier,
                                              String courierName, String courierAwb) {
        boolean eligible = RTO_ELIGIBLE_STATUSES.contains(order.getOrderStatus());
        String message = eligible
                ? "Order " + order.getOrderCode() + " can be marked RTO."
                : "Order " + order.getOrderCode() + " cannot be marked RTO (current status "
                        + order.getOrderStatus() + ").";
        return new RtoScanPreviewResponse(
                message, OrderSummaryResponse.from(order), eligible,
                scannedViaCourier, courierName, courierAwb);
    }
}
