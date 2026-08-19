package com.shifa.oms.product.dto;

import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.product.StockStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Product projection returned by the admin and catalog endpoints.
 *
 * <p>Admin responses carry the {@link #visibility} so an admin can see hidden
 * products; the catalog endpoints only ever return published products (Req 1.1).
 *
 * <p>Catalog &amp; Discovery adds (additively) the product's {@link #category},
 * its derived {@link #stockStatus} + {@link #stockQuantity} + {@link #trackInventory}
 * flag, and the {@link #featured} marker. Existing fields are unchanged.
 */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal mrp,
        BigDecimal salePrice,
        BigDecimal minimumRate,
        String hsnCode,
        BigDecimal gstRate,
        String wtMl,
        ProductVisibility visibility,
        CategoryRef category,
        StockStatus stockStatus,
        int stockQuantity,
        boolean trackInventory,
        Integer lowStockThreshold,
        boolean featured,
        List<ProductImageResponse> images,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /**
         * Average of APPROVED review ratings (Phase C: reviews & ratings), or
         * {@code null} when the product has no approved reviews so the storefront
         * can hide the stars. Additive: existing consumers ignore it.
         */
        Double averageRating,
        /** Count of APPROVED reviews contributing to {@link #averageRating}. */
        long reviewCount
) {

    public static ProductResponse from(Product product) {
        List<ProductImageResponse> images = product.getImages().stream()
                .map(ProductImageResponse::from)
                .toList();
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getMrp(),
                product.getSalePrice(),
                product.getMinimumRate(),
                product.getHsnCode(),
                product.getGstRate(),
                product.getWtMl(),
                product.getVisibility(),
                CategoryRef.from(product.getCategory()),
                product.stockStatus(),
                product.getStockQuantity(),
                product.isTrackInventory(),
                product.getLowStockThreshold(),
                product.isFeatured(),
                images,
                product.getCreatedAt(),
                product.getUpdatedAt(),
                null,
                0L);
    }

    /**
     * Returns a copy of this response carrying the given rating aggregate
     * (Phase C). Used by the catalog read paths to surface stars on cards/detail
     * without an extra round-trip; all other fields are preserved.
     */
    public ProductResponse withRating(Double averageRating, long reviewCount) {
        return new ProductResponse(
                id, sku, name, description, mrp, salePrice, minimumRate, hsnCode, gstRate, wtMl,
                visibility, category, stockStatus, stockQuantity, trackInventory, lowStockThreshold,
                featured, images, createdAt, updatedAt, averageRating, reviewCount);
    }
}
