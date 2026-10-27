package com.shifa.oms.order.dto;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compact order projection returned by the search endpoint
 * {@code GET /api/orders?search=} (Req 22.1). Omits line items and address
 * detail for a lightweight result list.
 */
public record OrderSummaryResponse(
        Long id,
        String orderCode,
        String customerName,
        String customerMobile,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        BigDecimal codAmount,
        LocalDateTime createdAt,
        // The mirrored QuikShipX status label (Pending / Confirmed / Tracking ID
        // Assigned / In Transit / …), shown as a chip on the Orders list. Null when
        // the order was never published to QuikShipX. Populated by the admin list
        // read path (batch-loaded); null on the lightweight search results.
        String quikShipXStatus,
        // QuikShipX's own order id (the number quoted to track on their portal) and
        // the allotted AWB, surfaced so the Orders list/chip can show them too.
        String quikShipXOrderId,
        String quikShipXAwb,
        // The name of the salesperson who punched the order (resolved from
        // created_by; full name, else username; null when unknown), so the Orders
        // table can show who triggered each order. Batch-loaded by the admin list
        // read path; null on the lightweight search results.
        String salespersonName,
        // The order's provenance (SALESPERSON / STOREFRONT / SHOPIFY), so the Orders
        // list can tag a Shopify-imported order with a badge. Always populated from
        // the entity (unlike the batch-loaded enrichment fields above).
        OrderSource source,
        // How the order is fulfilled (QUIKSHIPX / IN_HOUSE), so the Orders list can
        // show a "Courier Partner" column. Always populated from the entity.
        DeliveryMethod deliveryMethod,
        // The originating Shopify order id for a Shopify-imported order (null
        // otherwise), so the Orders list can show it in the salesperson column for
        // a Shopify order. Always populated from the entity.
        String shopifyOrderId,
        // --- COD remittance settlement (V99 Orders-list enrichment) -------------
        // Whether the courier has remitted this order's COD: NONE (nothing settled),
        // SETTLED (paid in full) or PARTIAL (short-paid, a shortfall remains). Drives
        // a settled/partial icon + tooltip on the Orders list. Null/NONE when there
        // is no settled COD receivable. Batch-loaded by the admin list read path.
        CodSettlementStatus codSettlementStatus,
        BigDecimal codSettledAmount,
        BigDecimal codSettledShortfall,
        java.time.LocalDate codSettledDate
) {

    /** The courier-remittance settlement state of an order's COD, for the Orders-list icon. */
    public enum CodSettlementStatus {
        NONE, SETTLED, PARTIAL
    }

    public static OrderSummaryResponse from(OrderEntity order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getOrderStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                order.getCodAmount(),
                order.getCreatedAt(),
                null,
                null,
                null,
                null,
                order.getSource(),
                order.getDeliveryMethod(),
                order.getShopifyOrderId(),
                CodSettlementStatus.NONE,
                null,
                null,
                null);
    }

    /** Returns a copy carrying the QuikShipX mirror fields (Orders-list enrichment). */
    public OrderSummaryResponse withQuikShip(String quikShipXStatus, String quikShipXOrderId,
                                             String quikShipXAwb) {
        return new OrderSummaryResponse(id, orderCode, customerName, customerMobile, orderStatus,
                paymentStatus, totalAmount, codAmount, createdAt,
                quikShipXStatus, quikShipXOrderId, quikShipXAwb, salespersonName, source,
                deliveryMethod, shopifyOrderId,
                codSettlementStatus, codSettledAmount, codSettledShortfall, codSettledDate);
    }

    /** Returns a copy carrying the resolved salesperson name (Orders-list enrichment). */
    public OrderSummaryResponse withSalesperson(String salespersonName) {
        return new OrderSummaryResponse(id, orderCode, customerName, customerMobile, orderStatus,
                paymentStatus, totalAmount, codAmount, createdAt,
                quikShipXStatus, quikShipXOrderId, quikShipXAwb, salespersonName, source,
                deliveryMethod, shopifyOrderId,
                codSettlementStatus, codSettledAmount, codSettledShortfall, codSettledDate);
    }

    /**
     * Returns a copy carrying the COD remittance-settlement fields (Orders-list
     * enrichment): the settled/partial status, the amount the courier remitted,
     * any shortfall still outstanding, and the settlement date.
     */
    public OrderSummaryResponse withSettlement(CodSettlementStatus status, BigDecimal settledAmount,
                                               BigDecimal shortfall, java.time.LocalDate settledDate) {
        return new OrderSummaryResponse(id, orderCode, customerName, customerMobile, orderStatus,
                paymentStatus, totalAmount, codAmount, createdAt,
                quikShipXStatus, quikShipXOrderId, quikShipXAwb, salespersonName, source,
                deliveryMethod, shopifyOrderId,
                status, settledAmount, shortfall, settledDate);
    }
}
