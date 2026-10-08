package com.shifa.oms.insights.domain;

/**
 * The entity kind an {@link Insight} is scoped to (design &sect;Pure domain).
 * The companion {@code scopeRefId} on an {@link Insight} points at the concrete
 * row of this kind, except for {@link #GLOBAL} insights which carry no reference
 * (persisted as the sentinel {@code 0} to keep the natural key unique).
 *
 * <ul>
 *   <li>{@link #GLOBAL} — operation-wide (sales, returns, COD, lead-source).</li>
 *   <li>{@link #PRODUCT} — a specific product ({@code scopeRefId = productId}).</li>
 *   <li>{@link #COURIER} — a courier company ({@code scopeRefId = courierCompanyId}).</li>
 *   <li>{@link #SALESPERSON} — a salesperson user ({@code scopeRefId = userId}).</li>
 *   <li>{@link #ORDER} — a specific order ({@code scopeRefId = orderId}).</li>
 * </ul>
 */
public enum InsightScope {
    GLOBAL,
    PRODUCT,
    COURIER,
    SALESPERSON,
    ORDER
}
