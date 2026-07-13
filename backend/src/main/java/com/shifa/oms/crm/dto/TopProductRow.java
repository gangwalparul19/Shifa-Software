package com.shifa.oms.crm.dto;

import java.math.BigDecimal;

/**
 * A product a customer has bought, aggregated across their orders for the 360
 * profile "products bought" list (FEATURE-ROADMAP §1.1).
 *
 * @param productId   the product id (nullable for legacy free-text lines)
 * @param productName the product name (snapshotted on the order line)
 * @param quantity    total units bought across the customer's orders
 * @param amount      total spend on this product (sum of line totals)
 */
public record TopProductRow(
        Long productId,
        String productName,
        long quantity,
        BigDecimal amount
) {
}
