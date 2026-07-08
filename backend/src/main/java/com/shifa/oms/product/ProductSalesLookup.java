package com.shifa.oms.product;

import com.shifa.oms.product.dto.ProductSalesStatsResponse;

/**
 * Read-only lookup of a product's current-month sales stats (revenue + order
 * count). Declared in the product package so {@link ProductService} can expose
 * the product-detail "Sales Overview" figures without depending on the order
 * module; the order module supplies the implementation (mirroring
 * {@link ProductRatingLookup}). Kept optional (may be absent) so the product
 * module stays usable in isolation, in which case the stats read as zero.
 */
public interface ProductSalesLookup {

    /**
     * The product's sales stats for the current calendar month, excluding orders
     * that never produced sellable revenue. Returns
     * {@link ProductSalesStatsResponse#ZERO} when the product has no qualifying
     * sales this month.
     */
    ProductSalesStatsResponse statsFor(Long productId);
}
