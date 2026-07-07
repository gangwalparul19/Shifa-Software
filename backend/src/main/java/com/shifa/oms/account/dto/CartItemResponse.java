package com.shifa.oms.account.dto;

import com.shifa.oms.product.Product;

import java.math.BigDecimal;

/**
 * A saved cart line as a product summary plus the saved quantity, for the
 * storefront cart view. Only the fields the cart needs are exposed (id, sku,
 * name, price, primary image key) alongside the quantity so the payload stays
 * lightweight — reusing the projection style of {@link WishlistItemResponse}.
 *
 * @param productId the product id
 * @param sku       the product SKU
 * @param name      the product name
 * @param salePrice the current sale price (what the customer pays per unit)
 * @param mrp       the maximum retail price (for strike-through display)
 * @param imageKey  the primary image object key, or {@code null} when none
 * @param quantity  the saved quantity for this product (1..999)
 */
public record CartItemResponse(
        Long productId,
        String sku,
        String name,
        BigDecimal salePrice,
        BigDecimal mrp,
        String imageKey,
        int quantity) {

    public static CartItemResponse from(Product product, int quantity) {
        String imageKey = product.getImages().isEmpty()
                ? null
                : product.getImages().get(0).getObjectKey();
        return new CartItemResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getSalePrice(),
                product.getMrp(),
                imageKey,
                quantity);
    }
}
