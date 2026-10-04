package com.shifa.oms.order;

/**
 * Where an {@link OrderEntity} originated (design: {@code orders.source}).
 *
 * <ul>
 *   <li>{@link #STOREFRONT} — placed by a customer through the public checkout
 *       ({@code POST /api/checkout}); unpaid (COD) at placement.</li>
 *   <li>{@link #SALESPERSON} — punched by a salesperson from a WhatsApp/Instagram
 *       enquiry ({@code POST /api/orders}), Requirement 7.</li>
 * </ul>
 */
public enum OrderSource {
    STOREFRONT,
    SALESPERSON,

    /**
     * Imported from the Shopify storefront via the {@code orders/create} webhook
     * ({@code POST /api/webhooks/shopify/orders}). The customer placed the order
     * on Shopify; Shifa's OMS mirrors it so the fulfilment team sees it here,
     * clearly tagged as a Shopify order.
     */
    SHOPIFY,

    /**
     * An in-shop (POS / counter) sale punched by an ADMIN for a walk-in customer
     * ({@code POST /api/orders/store}). The customer pays at the counter (full or
     * partial) and leaves with the goods, so a store order needs no payment
     * screenshot and no delivery partner (it is always a {@code COUNTER_SALE} →
     * in-house), may include ad-hoc items (e.g. a consultation fee) alongside
     * catalogue products, and is auto-approved (a fully-paid one is closed
     * immediately). Shown as its own "Store (POS)" channel on the dashboard.
     */
    STORE
}
