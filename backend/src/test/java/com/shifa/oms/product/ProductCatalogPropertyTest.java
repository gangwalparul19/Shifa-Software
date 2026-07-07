package com.shifa.oms.product;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the catalog/search predicate.
 *
 * Feature: shifa-herbal-remedies, Property 16: Catalog and search show only
 * matching published products. For any generated set of products (each either
 * PUBLISHED or HIDDEN) and any query string, the catalog exposes exactly the
 * published products, and search returns exactly the published products whose
 * name or SKU contains the (case-insensitive) query; hidden products never
 * appear.
 *
 * Validates: Requirements 1.1, 1.3, 6.4
 */
class ProductCatalogPropertyTest {

    @Provide
    Arbitrary<Product> products() {
        Arbitrary<String> skus = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('A', 'Z', '0', '9', '-')
                .ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> names = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('A', 'Z', ' ', '0', '9')
                .ofMinLength(1).ofMaxLength(24);
        Arbitrary<ProductVisibility> visibilities = Arbitraries.of(ProductVisibility.class);
        return Combinators.combine(skus, names, visibilities)
                .as((sku, name, visibility) ->
                        new Product(sku, name, "desc", BigDecimal.ZERO, BigDecimal.ZERO, visibility));
    }

    @Provide
    Arbitrary<List<Product>> productSets() {
        return products().list().ofMinSize(0).ofMaxSize(30);
    }

    @Provide
    Arbitrary<String> queries() {
        return Arbitraries.strings()
                .withCharRange('a', 'z').withChars('A', 'Z', ' ', '0', '9', '-')
                .ofMinLength(0).ofMaxLength(6);
    }

    // Feature: shifa-herbal-remedies, Property 16: Catalog and search show only matching published products
    @Property(tries = 300)
    void catalogExposesExactlyPublishedProducts(@ForAll("productSets") List<Product> all) {
        List<Product> catalog = ProductCatalog.catalog(all);

        // Every catalog entry is published; no hidden product leaks through.
        assertThat(catalog).allMatch(p -> p.getVisibility() == ProductVisibility.PUBLISHED);
        long expectedPublished = all.stream()
                .filter(p -> p.getVisibility() == ProductVisibility.PUBLISHED)
                .count();
        assertThat(catalog).hasSize((int) expectedPublished);
    }

    // Feature: shifa-herbal-remedies, Property 16: Catalog and search show only matching published products
    @Property(tries = 500)
    void searchReturnsExactlyPublishedProductsMatchingQuery(
            @ForAll("productSets") List<Product> all,
            @ForAll("queries") String query) {

        List<Product> results = ProductCatalog.search(all, query);

        // 1) Every result is published and genuinely matches the query.
        for (Product p : results) {
            assertThat(p.getVisibility()).isEqualTo(ProductVisibility.PUBLISHED);
            if (!query.isBlank()) {
                String needle = query.trim().toLowerCase(Locale.ROOT);
                boolean matches = p.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || p.getSku().toLowerCase(Locale.ROOT).contains(needle);
                assertThat(matches)
                        .as("result must match query on name or sku")
                        .isTrue();
            }
        }

        // 2) No published-and-matching product is omitted (completeness).
        long expected = all.stream().filter(p -> ProductCatalog.matches(p, query)).count();
        assertThat(results).hasSize((int) expected);

        // 3) Hidden products never appear regardless of query.
        assertThat(results).noneMatch(p -> p.getVisibility() == ProductVisibility.HIDDEN);
    }
}
