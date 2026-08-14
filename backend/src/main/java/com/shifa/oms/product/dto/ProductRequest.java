package com.shifa.oms.product.dto;

import com.shifa.oms.product.ProductVisibility;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create/update payload for a product (Req 6.1, 6.3, 6.4).
 *
 * <p>Bean-validation failures are rendered as the standard 400 error envelope by
 * the global exception handler. Monetary values are {@code DECIMAL(12,2)} and
 * must be non-negative.
 */
public record ProductRequest(
        @NotBlank(message = "sku is required")
        @Size(max = 64, message = "sku must be at most 64 characters")
        String sku,

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        String description,

        @NotNull(message = "mrp is required")
        @DecimalMin(value = "0.00", message = "mrp must not be negative")
        @Digits(integer = 10, fraction = 2, message = "mrp must be a DECIMAL(12,2) value")
        BigDecimal mrp,

        @NotNull(message = "salePrice is required")
        @DecimalMin(value = "0.00", message = "salePrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "salePrice must be a DECIMAL(12,2) value")
        BigDecimal salePrice,

        /**
         * Optional per-product MINIMUM sale price (order-entry floor). {@code null}
         * disables the price band for this product. When set, the service enforces
         * {@code minPrice <= salePrice <= mrp}.
         */
        @DecimalMin(value = "0.00", message = "minPrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "minPrice must be a DECIMAL(12,2) value")
        BigDecimal minPrice,

        @Size(max = 20, message = "hsnCode must be at most 20 characters")
        String hsnCode,

        /** Optional pack size / weight label (e.g. 100ML, 350gm, Combo). */
        @Size(max = 40, message = "packSize must be at most 40 characters")
        String packSize,

        /** Optional per-product GST rate percent; null falls back to the settings default. */
        @DecimalMin(value = "0.00", message = "gstRate must not be negative")
        @DecimalMax(value = "100.00", message = "gstRate must not exceed 100")
        @Digits(integer = 3, fraction = 2, message = "gstRate must be a DECIMAL(5,2) value")
        BigDecimal gstRate,

        @NotNull(message = "visibility is required")
        ProductVisibility visibility,

        /** Optional category id; null leaves the product uncategorised (Catalog & Discovery). */
        Long categoryId,

        /** On-hand units; only meaningful when {@link #trackInventory} is true. Defaults to 0. */
        @PositiveOrZero(message = "stockQuantity must not be negative")
        Integer stockQuantity,

        /** When true the product tracks stock; when false/null it always reads in-stock. */
        Boolean trackInventory,

        /** Optional per-product low-stock threshold override; null uses the settings default. */
        @PositiveOrZero(message = "lowStockThreshold must not be negative")
        Integer lowStockThreshold,

        /** When true the product joins the storefront featured collection. */
        Boolean featured
) {
}
