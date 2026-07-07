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
    SALESPERSON
}
