package com.shifa.oms.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link RelatedProducts} "you may also like" heuristic
 * (Catalog & Discovery): excludes self + out-of-stock, prefers same category,
 * then featured, capped at the limit with graceful fallback.
 */
class RelatedProductsTest {

    private static Category category(String slug) {
        return new Category(slug, slug, null, 0, true);
    }

    private static Product product(String name, Category category, boolean featured,
                                   boolean trackInventory, int qty) {
        Product p = new Product(name, name, "d",
                new BigDecimal("100.00"), new BigDecimal("90.00"), ProductVisibility.PUBLISHED);
        p.setCategory(category);
        p.setFeatured(featured);
        p.setTrackInventory(trackInventory);
        p.setStockQuantity(qty);
        return p;
    }

    private final Category immunity = category("immunity");
    private final Category juices = category("juices");

    @Test
    void excludesTheTargetItself() {
        Product target = product("Target", immunity, false, false, 0);
        Product other = product("Other", juices, false, false, 0);
        assertThat(RelatedProducts.select(target, List.of(target, other), 4))
                .containsExactly(other);
    }

    @Test
    void excludesOutOfStockCandidates() {
        Product target = product("Target", immunity, false, false, 0);
        Product outOfStock = product("Sold Out", immunity, false, true, 0);
        Product inStock = product("Available", juices, false, false, 0);
        assertThat(RelatedProducts.select(target, List.of(outOfStock, inStock), 4))
                .containsExactly(inStock);
    }

    @Test
    void prefersSameCategoryThenFeatured() {
        Product target = product("Target", immunity, false, false, 0);
        Product sameCat = product("Same Category", immunity, false, false, 0);
        Product otherFeatured = product("Other Featured", juices, true, false, 0);
        Product otherPlain = product("Other Plain", juices, false, false, 0);

        List<Product> result = RelatedProducts.select(
                target, List.of(otherPlain, otherFeatured, sameCat), 4);

        // Same category first, then featured (other), then plain other.
        assertThat(result).containsExactly(sameCat, otherFeatured, otherPlain);
    }

    @Test
    void capsAtLimit() {
        Product target = product("Target", immunity, false, false, 0);
        Product a = product("A", immunity, false, false, 0);
        Product b = product("B", immunity, false, false, 0);
        Product c = product("C", immunity, false, false, 0);

        assertThat(RelatedProducts.select(target, List.of(a, b, c), 2)).hasSize(2);
    }

    @Test
    void fallsBackToOtherCategoriesWhenNoSameCategory() {
        Product target = product("Target", immunity, false, false, 0);
        Product other1 = product("Beta", juices, false, false, 0);
        Product other2 = product("Alpha", juices, true, false, 0);

        // No same-category candidate; featured ranks first, then name order.
        assertThat(RelatedProducts.select(target, List.of(other1, other2), 4))
                .containsExactly(other2, other1);
    }

    @Test
    void zeroLimitReturnsEmpty() {
        Product target = product("Target", immunity, false, false, 0);
        Product other = product("Other", juices, false, false, 0);
        assertThat(RelatedProducts.select(target, List.of(other), 0)).isEmpty();
    }
}
