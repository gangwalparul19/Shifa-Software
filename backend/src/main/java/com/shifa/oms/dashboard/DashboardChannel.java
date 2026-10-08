package com.shifa.oms.dashboard;

import com.shifa.oms.order.OrderSource;

import java.util.Locale;

/**
 * The order channel the admin dashboard is scoped to. {@link #PORTAL} is every
 * order our own team punched online (salesperson / legacy storefront — i.e.
 * neither Shopify nor an in-shop store sale), {@link #SHOPIFY} is the
 * auto-imported Shopify orders, {@link #STORE} is in-shop (POS / counter) sales,
 * and {@link #ALL} is everything combined. Mirrors the partition used by
 * {@code ChannelSummaryService} so the numbers agree across the app.
 */
public enum DashboardChannel {
    ALL,
    PORTAL,
    SHOPIFY,
    STORE;

    /** Whether an order from {@code source} belongs to this channel. */
    public boolean matches(OrderSource source) {
        return switch (this) {
            case ALL -> true;
            case SHOPIFY -> source == OrderSource.SHOPIFY;
            case STORE -> source == OrderSource.STORE;
            // Portal = our own online orders: everything that is NOT Shopify and NOT
            // an in-shop store sale (so a store order shows only under STORE/ALL).
            case PORTAL -> source != OrderSource.SHOPIFY && source != OrderSource.STORE;
        };
    }

    /** Whether Portal-side data (salespeople, approval queue) is in scope. */
    public boolean includesPortal() {
        return this == ALL || this == PORTAL;
    }

    /** Whether Shopify-side data (label printing, stuck orders) is in scope. */
    public boolean includesShopify() {
        return this == ALL || this == SHOPIFY;
    }

    /** Parses leniently; blank means {@link #ALL}. */
    public static DashboardChannel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return DashboardChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
