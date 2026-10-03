package com.shifa.oms.dashboard;

import com.shifa.oms.order.OrderSource;

import java.util.Locale;

/**
 * The order channel the admin dashboard is scoped to. {@link #PORTAL} is every
 * order our own team punched (salesperson / legacy storefront — i.e. not
 * Shopify), {@link #SHOPIFY} is the auto-imported Shopify orders, and
 * {@link #ALL} is both combined. Mirrors the Portal/Shopify partition used by
 * {@code ChannelSummaryService} so the numbers agree across the app.
 */
public enum DashboardChannel {
    ALL,
    PORTAL,
    SHOPIFY;

    /** Whether an order from {@code source} belongs to this channel. */
    public boolean matches(OrderSource source) {
        return switch (this) {
            case ALL -> true;
            case SHOPIFY -> source == OrderSource.SHOPIFY;
            case PORTAL -> source != OrderSource.SHOPIFY;
        };
    }

    /** Whether Portal-side data (salespeople, approval queue) is in scope. */
    public boolean includesPortal() {
        return this != SHOPIFY;
    }

    /** Whether Shopify-side data (label printing, stuck orders) is in scope. */
    public boolean includesShopify() {
        return this != PORTAL;
    }

    /** Parses leniently; blank means {@link #ALL}. */
    public static DashboardChannel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return DashboardChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
