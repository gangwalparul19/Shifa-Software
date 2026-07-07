package com.shifa.oms.product;

import java.math.BigDecimal;

/**
 * Immutable filter/sort criteria for the public catalog listing (Catalog &
 * Discovery). All fields are optional; a {@code null} means "no constraint".
 *
 * @param query        free-text search over name/SKU (existing {@code q} param)
 * @param categorySlug category slug to filter by (resolved from slug or id)
 * @param minPrice     inclusive minimum sale price
 * @param maxPrice     inclusive maximum sale price
 * @param inStockOnly  when true, exclude {@link StockStatus#OUT_OF_STOCK} products
 * @param featuredOnly when true, only featured products
 * @param sort         server-side sort order (never null; defaults to relevance)
 */
public record CatalogQuery(
        String query,
        String categorySlug,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStockOnly,
        boolean featuredOnly,
        CatalogSort sort) {

    public CatalogQuery {
        if (sort == null) {
            sort = CatalogSort.RELEVANCE;
        }
    }

    /** An unfiltered query in relevance order (the plain catalog). */
    public static CatalogQuery all() {
        return new CatalogQuery(null, null, null, null, false, false, CatalogSort.RELEVANCE);
    }
}
