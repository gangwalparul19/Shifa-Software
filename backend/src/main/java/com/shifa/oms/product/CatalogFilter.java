package com.shifa.oms.product;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure filtering + sorting logic for the public catalog (Catalog & Discovery).
 *
 * <p>Kept as a standalone, database-free helper — like {@link ProductCatalog} —
 * so the predicate/ordering behaviour can be unit-tested in isolation. The
 * catalog service fetches the published products once and applies this to honour
 * the {@code q}, {@code category}, {@code minPrice}, {@code maxPrice},
 * {@code inStock}, {@code featured} and {@code sort} parameters. At the current
 * catalog scale this in-memory pass is cheap; the visibility + category cut is
 * still pushed to SQL by the repository where practical.
 */
public final class CatalogFilter {

    private CatalogFilter() {
    }

    /**
     * Applies the query's filters then sorts. Input products are assumed to be
     * the candidate set (e.g. published products); this never re-checks
     * visibility so callers control that upstream.
     */
    public static List<Product> apply(List<Product> products, CatalogQuery query) {
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(query, "query");
        return products.stream()
                .filter(p -> matches(p, query))
                .sorted(comparator(query.sort()))
                .toList();
    }

    /** Whether a product satisfies every active filter in the query. */
    public static boolean matches(Product product, CatalogQuery query) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(query, "query");

        if (!ProductCatalog.matches(product, query.query())) {
            return false;
        }
        if (query.categorySlug() != null && !query.categorySlug().isBlank()) {
            Category category = product.getCategory();
            if (category == null || !query.categorySlug().equalsIgnoreCase(category.getSlug())) {
                return false;
            }
        }
        BigDecimal price = product.getSalePrice() == null ? BigDecimal.ZERO : product.getSalePrice();
        if (query.minPrice() != null && price.compareTo(query.minPrice()) < 0) {
            return false;
        }
        if (query.maxPrice() != null && price.compareTo(query.maxPrice()) > 0) {
            return false;
        }
        if (query.inStockOnly() && product.stockStatus() == StockStatus.OUT_OF_STOCK) {
            return false;
        }
        if (query.featuredOnly() && !product.isFeatured()) {
            return false;
        }
        return true;
    }

    /**
     * The comparator for a sort option. Ties (and the RELEVANCE default) fall
     * back to a stable name-ascending order so results are deterministic.
     */
    public static Comparator<Product> comparator(CatalogSort sort) {
        Comparator<Product> byName = Comparator.comparing(
                p -> p.getName() == null ? "" : p.getName(), String.CASE_INSENSITIVE_ORDER);
        return switch (sort == null ? CatalogSort.RELEVANCE : sort) {
            case PRICE_ASC -> Comparator
                    .comparing(CatalogFilter::price)
                    .thenComparing(byName);
            case PRICE_DESC -> Comparator
                    .comparing(CatalogFilter::price).reversed()
                    .thenComparing(byName);
            case NAME_ASC -> byName;
            case NEWEST -> Comparator
                    .comparing(CatalogFilter::createdAtOrMin).reversed()
                    .thenComparing(byName);
            case RELEVANCE -> byName;
        };
    }

    private static BigDecimal price(Product product) {
        return product.getSalePrice() == null ? BigDecimal.ZERO : product.getSalePrice();
    }

    private static java.time.LocalDateTime createdAtOrMin(Product product) {
        return product.getCreatedAt() == null ? java.time.LocalDateTime.MIN : product.getCreatedAt();
    }
}
