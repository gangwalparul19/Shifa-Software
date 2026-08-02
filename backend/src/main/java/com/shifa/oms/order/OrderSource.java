package com.shifa.oms.order;

import java.util.List;

/**
 * The origin channel of an {@link OrderEntity} (design: {@code orders.source}).
 *
 * <ul>
 *   <li>{@link #SHOPIFY_API} — placed on the Shopify storefront and ingested into
 *       Shifa OMS from a Shopify order webhook. Read-only in Shifa OMS.</li>
 *   <li>{@link #SHIFA_ADMIN} — punched in the Shifa Admin Portal by an ADMIN,
 *       SALESPERSON or TEAM_LEAD ({@code POST /api/orders}).</li>
 *   <li>{@link #STOREFRONT} — <b>legacy</b>. The public checkout was removed when
 *       the store moved to Shopify; retained because historic rows still carry it.</li>
 *   <li>{@link #SALESPERSON} — <b>legacy</b>. The pre-Shopify name for a
 *       portal-punched order; retained because historic rows still carry it.</li>
 * </ul>
 *
 * <p>Distinct from {@link LeadSource}, which records the marketing channel the
 * enquiry arrived through. A {@code SHIFA_ADMIN} order can carry any lead source.
 *
 * <p>The two legacy values are never written by new code but must keep reading
 * correctly, so {@link #canonical()} folds them onto {@link #SHIFA_ADMIN} for
 * filtering, reporting and dashboard grouping (Req 1.6).
 */
public enum OrderSource {

    STOREFRONT,
    SALESPERSON,
    SHOPIFY_API,
    SHIFA_ADMIN;

    /**
     * The channel this stored value counts as. Both legacy values were
     * Shifa-originated, so they fold onto {@link #SHIFA_ADMIN}; {@code SHOPIFY_API}
     * is its own channel (Req 1.6).
     *
     * <p>Total by construction: every constant is covered, so adding a constant
     * without deciding its channel is a compile error rather than a silent default.
     */
    public OrderSource canonical() {
        return switch (this) {
            case STOREFRONT, SALESPERSON, SHIFA_ADMIN -> SHIFA_ADMIN;
            case SHOPIFY_API -> SHOPIFY_API;
        };
    }

    /** Whether this order originated on the Shopify storefront. */
    public boolean isShopify() {
        return canonical() == SHOPIFY_API;
    }

    /**
     * Every stored value that {@link #canonical()}-folds onto this channel.
     *
     * <p>This is what a channel filter must match against, because the column
     * still holds legacy values: filtering for {@code SHIFA_ADMIN} has to match
     * {@code SALESPERSON} and {@code STOREFRONT} rows too (Req 11.2).
     */
    public List<OrderSource> storedEquivalents() {
        OrderSource target = canonical();
        return List.of(values()).stream().filter(v -> v.canonical() == target).toList();
    }
}
