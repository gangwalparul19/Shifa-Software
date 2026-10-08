package com.shifa.oms.product.dto;

import java.math.BigDecimal;

/**
 * Read-only per-product sales stats for the product-detail "Sales Overview"
 * card, returned by {@code GET /api/admin/products/{id}/stats}.
 *
 * <p>Both figures are scoped to the CURRENT calendar month and computed from the
 * order line items for the product, excluding orders that never produced
 * sellable revenue (REJECTED / CANCELLED — the same exclusion the P&amp;L revenue
 * uses).
 *
 * @param salesThisMonth  sum of the product's line totals across qualifying
 *                        orders created this month (never {@code null}; 0.00 when
 *                        the product has no qualifying sales)
 * @param ordersThisMonth the number of distinct qualifying orders this month
 *                        containing the product (0 when none)
 */
public record ProductSalesStatsResponse(
        BigDecimal salesThisMonth,
        long ordersThisMonth
) {

    /** The empty aggregate: no sales for the product this month. */
    public static final ProductSalesStatsResponse ZERO =
            new ProductSalesStatsResponse(BigDecimal.ZERO.setScale(2), 0L);
}
