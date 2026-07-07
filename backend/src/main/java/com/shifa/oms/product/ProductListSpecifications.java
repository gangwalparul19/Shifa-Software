package com.shifa.oms.product;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@link Specification} for the admin products list/table
 * (ROADMAP 2.2 "Wave 2" server-side filtering). All filters are optional and
 * AND-combined; an empty filter set matches every product (published + hidden),
 * mirroring the existing admin grid semantics.
 *
 * <p>Supported filters:
 * <ul>
 *   <li>{@code q} — case-insensitive substring over name or SKU;</li>
 *   <li>{@code categoryId} — exact category id (resolved from a slug/id param by
 *       the service);</li>
 *   <li>{@code visibility} — PUBLISHED or HIDDEN;</li>
 *   <li>{@code stockStatus} — IN_STOCK / LOW_STOCK / OUT_OF_STOCK, computed from
 *       the {@code track_inventory} flag, {@code stock_quantity}, and the
 *       per-product {@code low_stock_threshold} (falling back to the default
 *       {@link StockStatus#LOW_STOCK_THRESHOLD}).</li>
 * </ul>
 */
public final class ProductListSpecifications {

    private ProductListSpecifications() {
    }

    public static Specification<Product> build(String q, Long categoryId,
                                               ProductVisibility visibility,
                                               StockStatus stockStatus) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("sku")), like)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (visibility != null) {
                predicates.add(cb.equal(root.get("visibility"), visibility));
            }
            if (stockStatus != null) {
                predicates.add(stockPredicate(root, cb, stockStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Reproduces {@link StockStatus#of(boolean, int, int)} in SQL: a non-tracked
     * product is always IN_STOCK; a tracked one is OUT_OF_STOCK at qty &le; 0,
     * LOW_STOCK up to its effective low-stock threshold, and IN_STOCK above it.
     * The effective threshold is {@code COALESCE(low_stock_threshold, DEFAULT)}.
     */
    private static Predicate stockPredicate(jakarta.persistence.criteria.Root<Product> root,
                                            jakarta.persistence.criteria.CriteriaBuilder cb,
                                            StockStatus stockStatus) {
        Expression<Boolean> tracked = root.get("trackInventory");
        Expression<Integer> qty = root.get("stockQuantity");
        Expression<Integer> threshold = cb.coalesce(
                root.get("lowStockThreshold"), StockStatus.LOW_STOCK_THRESHOLD);

        return switch (stockStatus) {
            case OUT_OF_STOCK -> cb.and(
                    cb.isTrue(tracked),
                    cb.lessThanOrEqualTo(qty, 0));
            case LOW_STOCK -> cb.and(
                    cb.isTrue(tracked),
                    cb.greaterThan(qty, 0),
                    cb.lessThanOrEqualTo(qty, threshold));
            case IN_STOCK -> cb.or(
                    cb.isFalse(tracked),
                    cb.greaterThan(qty, threshold));
        };
    }
}
