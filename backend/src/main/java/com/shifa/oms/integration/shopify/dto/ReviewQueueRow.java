package com.shifa.oms.integration.shopify.dto;

import com.shifa.oms.integration.shopify.OrderReviewReason;
import com.shifa.oms.integration.shopify.ReviewReason;
import com.shifa.oms.order.OrderEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One order awaiting a human look after Shopify ingestion (Req 3.9).
 *
 * <p>Carries the Shopify order number alongside the Shifa order code, because an admin
 * reconciling against the store searches by the number the buyer and Shopify both see.
 * Every recorded reason is listed with its detail, so the admin knows what to fix without
 * opening the raw payload.
 *
 * @param orderId             the Shifa order id, for the deep link into the order drawer
 * @param orderCode           the Shifa order code
 * @param shopifyOrderNumber  the human-facing Shopify order name, e.g. {@code "#1042"}
 * @param shopifyOrderId      Shopify's numeric order id
 * @param customerName        the buyer's name as ingested
 * @param customerMobile      the normalised contact number, empty when none could be read
 * @param totalAmount         the order total, persisted exactly as Shopify supplied it
 * @param orderStatus         the order's current status
 * @param createdAt           when the order was created in Shifa
 * @param reasons             every reason recorded against this order
 */
public record ReviewQueueRow(
        Long orderId,
        String orderCode,
        String shopifyOrderNumber,
        String shopifyOrderId,
        String customerName,
        String customerMobile,
        BigDecimal totalAmount,
        String orderStatus,
        LocalDateTime createdAt,
        List<Reason> reasons) {

    /**
     * One reason, with both the canned explanation and the specific detail.
     *
     * @param reason      the enum name, for the frontend to style
     * @param description what this reason means in general
     * @param detail      what was actually wrong on this order, e.g. the unmatched SKU
     */
    public record Reason(String reason, String description, String detail) {

        public static Reason from(OrderReviewReason entity) {
            ReviewReason reason = entity.getReason();
            return new Reason(reason.name(), reason.description(), entity.getDetail());
        }
    }

    public static ReviewQueueRow from(OrderEntity order, List<OrderReviewReason> reasons) {
        return new ReviewQueueRow(
                order.getId(),
                order.getOrderCode(),
                order.getShopifyOrderNumber(),
                order.getShopifyOrderId(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getTotalAmount(),
                order.getOrderStatus() == null ? null : order.getOrderStatus().name(),
                order.getCreatedAt(),
                reasons.stream().map(Reason::from).toList());
    }
}
