package com.shifa.oms.product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure "you may also like" heuristic (Catalog & Discovery).
 *
 * <p>Given a target product and a candidate pool, selects up to {@code limit}
 * suggestions using this documented ranking, kept database-free so it is
 * unit-testable:
 *
 * <ol>
 *   <li><b>Exclude</b> the target itself and any {@link StockStatus#OUT_OF_STOCK}
 *       candidate (never suggest something that can't be bought).</li>
 *   <li><b>Prefer same category:</b> candidates sharing the target's category
 *       rank first.</li>
 *   <li>Within each of those groups, <b>featured</b> products rank ahead of the
 *       rest, then a stable name-ascending order breaks remaining ties.</li>
 * </ol>
 *
 * <p>The result is capped at {@code limit}, so when there aren't enough
 * same-category products it naturally falls back to featured/other products.
 */
public final class RelatedProducts {

    /** Default number of suggestions surfaced on the product detail page. */
    public static final int DEFAULT_LIMIT = 4;

    private RelatedProducts() {
    }

    public static List<Product> select(Product target, List<Product> candidates) {
        return select(target, candidates, DEFAULT_LIMIT);
    }

    public static List<Product> select(Product target, List<Product> candidates, int limit) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(candidates, "candidates");
        if (limit <= 0) {
            return List.of();
        }

        Long targetId = target.getId();
        String targetCategory = categoryKey(target);

        List<Product> eligible = new ArrayList<>();
        for (Product candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (targetId != null && targetId.equals(candidate.getId())) {
                continue; // never suggest the product itself
            }
            if (candidate == target) {
                continue; // identity guard for transient (unsaved) products in tests
            }
            if (candidate.stockStatus() == StockStatus.OUT_OF_STOCK) {
                continue; // don't suggest unbuyable products
            }
            eligible.add(candidate);
        }

        Comparator<Product> ranking = Comparator
                // same category first (0 ranks before 1)
                .comparingInt((Product p) -> sameCategoryRank(p, targetCategory))
                // featured before non-featured
                .thenComparingInt(p -> p.isFeatured() ? 0 : 1)
                // stable, deterministic tie-break
                .thenComparing(p -> p.getName() == null ? "" : p.getName(),
                        String.CASE_INSENSITIVE_ORDER);

        return eligible.stream().sorted(ranking).limit(limit).toList();
    }

    private static int sameCategoryRank(Product candidate, String targetCategory) {
        if (targetCategory == null) {
            return 1;
        }
        return targetCategory.equals(categoryKey(candidate)) ? 0 : 1;
    }

    /** A stable category key (its unique slug) or null when uncategorised. */
    private static String categoryKey(Product product) {
        Category category = product.getCategory();
        return category == null ? null : category.getSlug();
    }
}
