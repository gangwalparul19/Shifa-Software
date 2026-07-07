package com.shifa.oms.product.dto;

import com.shifa.oms.product.ProductImage;

/**
 * Image projection returned with product detail. {@code published} lets the
 * storefront decide whether to show the image or a placeholder (Req 1.4).
 */
public record ProductImageResponse(
        Long id,
        String objectKey,
        boolean published,
        int sortOrder
) {

    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(
                image.getId(),
                image.getObjectKey(),
                image.isPublished(),
                image.getSortOrder());
    }
}
