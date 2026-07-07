package com.shifa.oms.order;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure search-match and duplicate-detection logic for orders (Req 22.1, 22.2).
 *
 * <p>Kept free of persistence and Spring so it can be exercised exhaustively by
 * property-based tests (design "Property 22"), and so the SQL finder in
 * {@link OrderRepository} has a single, verifiable definition of what "matches"
 * means: a case-insensitive substring hit on the customer name, mobile number,
 * order code, numeric id, or courier AWB.
 */
public final class OrderSearchMatcher {

    private OrderSearchMatcher() {
    }

    /**
     * The searchable projection of an order — exactly the fields the search term
     * is tested against (Req 22.1). {@code awb} may be {@code null} before a
     * courier is assigned.
     */
    public record OrderSearchView(Long id, String orderCode, String customerName,
                                  String customerMobile, String awb) {
    }

    /**
     * Whether {@code view} matches the (case-insensitive, trimmed) search term.
     * A blank term matches everything (an unfiltered listing).
     */
    public static boolean matches(OrderSearchView view, String term) {
        Objects.requireNonNull(view, "view");
        if (term == null || term.isBlank()) {
            return true;
        }
        String needle = term.trim().toLowerCase(Locale.ROOT);
        return contains(view.customerName(), needle)
                || contains(view.customerMobile(), needle)
                || contains(view.orderCode(), needle)
                || contains(view.id() == null ? null : Long.toString(view.id()), needle)
                || contains(view.awb(), needle);
    }

    /** All orders matching the term, preserving input order (Req 22.1). */
    public static List<OrderSearchView> search(List<OrderSearchView> orders, String term) {
        Objects.requireNonNull(orders, "orders");
        return orders.stream().filter(o -> matches(o, term)).toList();
    }

    /** Count of prior orders for a mobile number, used for duplicate detection (Req 22.2). */
    public static long duplicateCount(List<OrderSearchView> orders, String mobile) {
        Objects.requireNonNull(orders, "orders");
        if (mobile == null || mobile.isBlank()) {
            return 0;
        }
        return orders.stream()
                .filter(o -> mobile.equals(o.customerMobile()))
                .count();
    }

    /** Whether one or more prior orders exist for a mobile number (Req 22.2). */
    public static boolean hasPriorOrders(List<OrderSearchView> orders, String mobile) {
        return duplicateCount(orders, mobile) > 0;
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
