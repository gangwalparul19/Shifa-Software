package com.shifa.oms.product;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure catalog/search predicate (Req 1.1, 1.3, 6.4).
 *
 * <p>This mirrors, in testable pure logic, the visibility + substring rule the
 * storefront applies: only {@link ProductVisibility#PUBLISHED} products are ever
 * exposed, and a search matches when its (trimmed, case-insensitive) query is a
 * substring of the product name or SKU. The {@link ProductRepository} pushes the
 * same rule into SQL; keeping the canonical definition here lets it be
 * property-tested without a database (design: correctness Property 16).
 */
public final class ProductCatalog {

    private ProductCatalog() {
    }

    /** Whether a product is visible in the public storefront (Req 6.4). */
    public static boolean isVisible(Product product) {
        Objects.requireNonNull(product, "product");
        return product.getVisibility() == ProductVisibility.PUBLISHED;
    }

    /**
     * Whether a product should appear for the given search query.
     *
     * <p>A hidden product never matches. A blank/absent query matches every
     * published product (the full catalog). Otherwise the query must be a
     * case-insensitive substring of the product name or SKU (Req 1.3).
     */
    public static boolean matches(Product product, String query) {
        if (!isVisible(product)) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        String name = product.getName() == null ? "" : product.getName().toLowerCase(Locale.ROOT);
        String sku = product.getSku() == null ? "" : product.getSku().toLowerCase(Locale.ROOT);
        return name.contains(needle) || sku.contains(needle);
    }

    /** The published catalog: every visible product (Req 1.1, 6.4). */
    public static List<Product> catalog(List<Product> products) {
        Objects.requireNonNull(products, "products");
        return products.stream().filter(ProductCatalog::isVisible).toList();
    }

    /** Published products matching the query (Req 1.3). */
    public static List<Product> search(List<Product> products, String query) {
        Objects.requireNonNull(products, "products");
        return products.stream().filter(p -> matches(p, query)).toList();
    }
}
