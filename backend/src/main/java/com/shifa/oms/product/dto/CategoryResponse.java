package com.shifa.oms.product.dto;

import com.shifa.oms.product.Category;

/**
 * Category projection returned by the public catalog and admin endpoints.
 *
 * <p>The public list returns only active categories (id/name/slug/description);
 * the admin list additionally carries {@link #sortOrder} and {@link #active} so
 * the management UI can reorder and (de)activate them.
 */
public record CategoryResponse(
        Long id,
        String name,
        String slug,
        String description,
        int sortOrder,
        boolean active
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getSortOrder(),
                category.isActive());
    }
}
