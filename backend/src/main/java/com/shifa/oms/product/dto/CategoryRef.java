package com.shifa.oms.product.dto;

import com.shifa.oms.product.Category;

/**
 * Lightweight category reference embedded in a {@link ProductResponse}
 * (id/slug/name), so the storefront can render and link a product's category
 * without a second request. Null when the product is uncategorised.
 */
public record CategoryRef(
        Long id,
        String slug,
        String name
) {

    public static CategoryRef from(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryRef(category.getId(), category.getSlug(), category.getName());
    }
}
