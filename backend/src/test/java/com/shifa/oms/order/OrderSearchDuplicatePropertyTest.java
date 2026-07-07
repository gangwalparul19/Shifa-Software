package com.shifa.oms.order;

import com.shifa.oms.order.OrderSearchMatcher.OrderSearchView;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the order search-match and duplicate-detection logic.
 *
 * Feature: shifa-herbal-remedies, Property 22: Search and duplicate detection.
 * For any generated set of orders and any query, search returns exactly the
 * orders whose name / mobile / order code / id / AWB contains the query
 * (case-insensitive), and duplicate detection reports whether prior orders exist
 * for a mobile number together with the exact count.
 *
 * Validates: Requirements 22.1, 22.2
 */
class OrderSearchDuplicatePropertyTest {

    @Provide
    Arbitrary<OrderSearchView> orderViews() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 5000);
        Arbitrary<String> codes = Arbitraries.strings()
                .withCharRange('A', 'Z').withChars('0', '9', '-')
                .ofMinLength(3).ofMaxLength(16);
        Arbitrary<String> names = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('A', 'Z', ' ')
                .ofMinLength(1).ofMaxLength(20);
        // A small pool of mobiles so duplicates arise naturally.
        Arbitrary<String> mobiles = Arbitraries.of(
                "9000000001", "9000000002", "9000000003", "9000000004");
        Arbitrary<String> awbs = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.strings().withCharRange('A', 'Z').withChars('0', '9')
                        .ofMinLength(6).ofMaxLength(12));
        return Combinators.combine(ids, codes, names, mobiles, awbs).as(OrderSearchView::new);
    }

    @Provide
    Arbitrary<List<OrderSearchView>> orderSets() {
        return orderViews().list().ofMinSize(0).ofMaxSize(40);
    }

    @Provide
    Arbitrary<String> queries() {
        return Arbitraries.strings()
                .withCharRange('a', 'z').withChars('A', 'Z', '0', '9', '-', ' ')
                .ofMinLength(0).ofMaxLength(5);
    }

    // Feature: shifa-herbal-remedies, Property 22: Search and duplicate detection
    // **Validates: Requirements 22.1, 22.2**
    @Property(tries = 500)
    void searchReturnsExactlyMatchingOrders(
            @ForAll("orderSets") List<OrderSearchView> orders,
            @ForAll("queries") String query) {

        List<OrderSearchView> results = OrderSearchMatcher.search(orders, query);

        // 1) Every result genuinely matches the query on one of the searchable fields.
        for (OrderSearchView view : results) {
            assertThat(OrderSearchMatcher.matches(view, query)).isTrue();
            if (!query.isBlank()) {
                String needle = query.trim().toLowerCase(Locale.ROOT);
                boolean hit = containsCi(view.customerName(), needle)
                        || containsCi(view.customerMobile(), needle)
                        || containsCi(view.orderCode(), needle)
                        || containsCi(Long.toString(view.id()), needle)
                        || containsCi(view.awb(), needle);
                assertThat(hit).isTrue();
            }
        }

        // 2) Completeness: no matching order is omitted, and order is preserved.
        List<OrderSearchView> expected =
                orders.stream().filter(o -> OrderSearchMatcher.matches(o, query)).toList();
        assertThat(results).containsExactlyElementsOf(expected);

        // 3) A blank query returns everything.
        if (query.isBlank()) {
            assertThat(results).containsExactlyElementsOf(orders);
        }
    }

    // Feature: shifa-herbal-remedies, Property 22: Search and duplicate detection
    // **Validates: Requirements 22.1, 22.2**
    @Property(tries = 500)
    void duplicateDetectionMatchesActualCount(
            @ForAll("orderSets") List<OrderSearchView> orders,
            @ForAll("mobiles") String mobile) {

        long count = OrderSearchMatcher.duplicateCount(orders, mobile);
        boolean hasPrior = OrderSearchMatcher.hasPriorOrders(orders, mobile);

        long expected = orders.stream()
                .filter(o -> mobile.equals(o.customerMobile()))
                .count();

        assertThat(count).isEqualTo(expected);
        assertThat(hasPrior).isEqualTo(expected > 0);
    }

    @Provide
    Arbitrary<String> mobiles() {
        return Arbitraries.of(
                "9000000001", "9000000002", "9000000003", "9000000004", "9999999999");
    }

    private static boolean containsCi(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
