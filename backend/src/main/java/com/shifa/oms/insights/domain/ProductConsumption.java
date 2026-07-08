package com.shifa.oms.insights.domain;

/**
 * A product's on-hand stock and its windowed SALE consumption (design
 * &sect;Pure domain, &sect;Reorder detail; Req 4.1–4.4), the input to low-stock
 * reorder suggestions.
 *
 * @param productId          the product id ({@code scopeRefId} of the insight)
 * @param productName        the product name, for the insight label
 * @param onHand             the latest on-hand quantity ({@code balance_after})
 * @param unitsSoldInWindow  units sold (SALE movements) over the lookback window
 * @param lookbackDays       the length of the consumption lookback window in days
 */
public record ProductConsumption(
        Long productId,
        String productName,
        int onHand,
        long unitsSoldInWindow,
        int lookbackDays) {
}
