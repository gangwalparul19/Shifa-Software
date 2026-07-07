package com.shifa.oms.account.dto;

import com.shifa.oms.product.Product;
import com.shifa.oms.product.StockStatus;

import java.math.BigDecimal;

/**
 * A wishlist entry as a product summary for the storefront wishlist view. Only
 * the fields the wishlist card needs are exposed (id, sku, name, price, stock,
 * primary image key) so the payload stays lightweight.
 */
public record WishlistItemResponse(
        Long productId,
        String sku,
        String name,
        BigDecimal mrp,
        BigDecimal salePrice,
        StockStatus stockStatus,
        boolean published,
        String imageKey) {

    public static WishlistItemResponse from(Product product) {
        String imageKey = product.getImages().isEmpty()
                ? null
                : product.getImages().get(0).getObjectKey();
        return new WishlistItemResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getMrp(),
                product.getSalePrice(),
                product.stockStatus(),
                product.isPublished(),
                imageKey);
    }
}
