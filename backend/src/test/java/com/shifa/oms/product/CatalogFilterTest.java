package com.shifa.oms.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link CatalogFilter} filtering + sorting logic
 * (Catalog & Discovery): category, price range, in-stock and featured filters,
 * plus the price/name sort orders.
 */
class CatalogFilterTest {

    private static Category category(String name, String slug) {
        return new Category(name, slug, null, 0, true);
    }

    private static Product product(String sku, String name, String price, Category category,
                                   boolean trackInventory, int qty, boolean featured) {
        Product p = new Product(sku, name, "desc",
                new BigDecimal("999.00"), new BigDecimal(price), ProductVisibility.PUBLISHED);
        p.setCategory(category);
        p.setTrackInventory(trackInventory);
        p.setStockQuantity(qty);
        p.setFeatured(featured);
        return p;
    }

    private final Category immunity = category("Immunity", "immunity");
    private final Category juices = category("Juices", "juices");

    private final Product ashwagandha = product("A", "Ashwagandha", "549.00", immunity, true, 40, true);
    private final Product giloy = product("G", "Giloy Juice", "219.00", juices, true, 60, false);
    private final Product amla = product("M", "Amla Juice", "199.00", juices, false, 0, false);
    private final Product neem = product("N", "Neem Face Wash", "299.00", immunity, true, 0, false);

    private List<Product> all() {
        return List.of(ashwagandha, giloy, amla, neem);
    }

    @Test
    void categoryFilterKeepsOnlyMatchingSlug() {
        CatalogQuery q = new CatalogQuery(null, "juices", null, null, false, false, CatalogSort.NAME_ASC);
        assertThat(CatalogFilter.apply(all(), q))
                .containsExactly(amla, giloy); // Amla, Giloy alphabetically
    }

    @Test
    void priceRangeFilterIsInclusive() {
        CatalogQuery q = new CatalogQuery(null, null,
                new BigDecimal("200.00"), new BigDecimal("300.00"), false, false, CatalogSort.PRICE_ASC);
        // 219 (Giloy) and 299 (Neem) are within [200,300]; 199 and 549 are out.
        assertThat(CatalogFilter.apply(all(), q)).containsExactly(giloy, neem);
    }

    @Test
    void inStockOnlyExcludesOutOfStock() {
        CatalogQuery q = new CatalogQuery(null, null, null, null, true, false, CatalogSort.NAME_ASC);
        // Neem is tracked with 0 qty → OUT_OF_STOCK, excluded. Amla is untracked → in stock.
        assertThat(CatalogFilter.apply(all(), q)).doesNotContain(neem);
        assertThat(CatalogFilter.apply(all(), q)).contains(amla, ashwagandha, giloy);
    }

    @Test
    void featuredOnlyKeepsFeatured() {
        CatalogQuery q = new CatalogQuery(null, null, null, null, false, true, CatalogSort.RELEVANCE);
        assertThat(CatalogFilter.apply(all(), q)).containsExactly(ashwagandha);
    }

    @Test
    void priceAscAndDescOrderBySalePrice() {
        CatalogQuery asc = new CatalogQuery(null, null, null, null, false, false, CatalogSort.PRICE_ASC);
        assertThat(CatalogFilter.apply(all(), asc))
                .containsExactly(amla, giloy, neem, ashwagandha); // 199,219,299,549

        CatalogQuery desc = new CatalogQuery(null, null, null, null, false, false, CatalogSort.PRICE_DESC);
        assertThat(CatalogFilter.apply(all(), desc))
                .containsExactly(ashwagandha, neem, giloy, amla);
    }

    @Test
    void nameAscOrdersAlphabetically() {
        CatalogQuery q = new CatalogQuery(null, null, null, null, false, false, CatalogSort.NAME_ASC);
        assertThat(CatalogFilter.apply(all(), q))
                .containsExactly(amla, ashwagandha, giloy, neem);
    }

    @Test
    void searchQueryStillApplies() {
        CatalogQuery q = new CatalogQuery("juice", null, null, null, false, false, CatalogSort.NAME_ASC);
        assertThat(CatalogFilter.apply(all(), q)).containsExactly(amla, giloy);
    }

    @Test
    void combinedFiltersIntersect() {
        // Juices category + in stock only → excludes nothing here (both juices in stock),
        // but add a price ceiling to keep only the cheapest.
        CatalogQuery q = new CatalogQuery(null, "juices",
                null, new BigDecimal("200.00"), true, false, CatalogSort.PRICE_ASC);
        assertThat(CatalogFilter.apply(all(), q)).containsExactly(amla);
    }
}
