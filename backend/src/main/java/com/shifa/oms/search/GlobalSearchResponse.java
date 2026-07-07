package com.shifa.oms.search;

import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unified admin global-search result (ROADMAP 2.2 "Wave 2"). Groups the top
 * matches across the three primary entities so the admin omni-search can render
 * a single dropdown. Each group is independently capped
 * ({@link GlobalSearchService#GROUP_LIMIT}) and read-only.
 *
 * <pre>
 * {
 *   "orders":    [{ id, orderCode, customerName, orderStatus, totalAmount }],
 *   "products":  [{ id, sku, name }],
 *   "customers": [{ id, name, mobile }]
 * }
 * </pre>
 */
public record GlobalSearchResponse(
        List<OrderHit> orders,
        List<ProductHit> products,
        List<CustomerHit> customers) {

    /** An order match. */
    public record OrderHit(
            Long id,
            String orderCode,
            String customerName,
            OrderStatus orderStatus,
            BigDecimal totalAmount) {
    }

    /** A product match. */
    public record ProductHit(Long id, String sku, String name) {
    }

    /** A registered customer match. */
    public record CustomerHit(Long id, String name, String mobile) {
    }

    /** An all-empty result, returned for a blank query. */
    public static GlobalSearchResponse empty() {
        return new GlobalSearchResponse(List.of(), List.of(), List.of());
    }
}
