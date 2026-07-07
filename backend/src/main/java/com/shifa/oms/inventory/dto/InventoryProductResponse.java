package com.shifa.oms.inventory.dto;

import com.shifa.oms.product.Product;
import com.shifa.oms.product.StockStatus;

/**
 * Stock-focused product projection returned by the admin inventory endpoints.
 *
 * @param id            the product id
 * @param sku           the product SKU
 * @param name          the product name
 * @param trackInventory whether the product tracks stock
 * @param stockQuantity the on-hand quantity
 * @param lowStockThreshold the effective (resolved) low-stock threshold
 * @param stockStatus   the derived status (IN_STOCK / LOW_STOCK / OUT_OF_STOCK)
 *                      using the effective threshold
 */
public record InventoryProductResponse(
        Long id,
        String sku,
        String name,
        boolean trackInventory,
        int stockQuantity,
        int lowStockThreshold,
        StockStatus stockStatus) {

    public static InventoryProductResponse from(Product product, int effectiveThreshold) {
        return new InventoryProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.isTrackInventory(),
                product.getStockQuantity(),
                effectiveThreshold,
                StockStatus.of(product.isTrackInventory(), product.getStockQuantity(), effectiveThreshold));
    }
}
