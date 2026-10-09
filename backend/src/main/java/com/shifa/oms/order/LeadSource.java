package com.shifa.oms.order;

/**
 * The origin channel of a customer lead, captured on order entry (design
 * §3.1, Req 4.1). Distinct from {@link OrderSource}, which records the
 * provenance of the order record itself (storefront vs salesperson): a
 * {@code SALESPERSON} order can carry any of these lead channels.
 *
 * <p>Persisted on {@code orders.lead_source} as {@code VARCHAR(20)} via
 * {@code @Enumerated(EnumType.STRING)}. The column is nullable so pre-existing
 * seeded rows (V22) remain valid and are reported as {@code UNSPECIFIED};
 * presence is required at the service/DTO layer for new salesperson orders.
 *
 * <ul>
 *   <li>{@link #WHATSAPP} — lead arrived over WhatsApp.</li>
 *   <li>{@link #INSTAGRAM} — lead arrived over Instagram.</li>
 *   <li>{@link #FACEBOOK} — lead arrived over Facebook.</li>
 *   <li>{@link #OFFLINE} — walk-in / phone / other offline channel.</li>
 *   <li>{@link #OTHER} — anything else; accepts an optional free-text note
 *       ({@code lead_source_note}, Req 4.5).</li>
 *   <li>{@link #COUNTER_SALE} — an in-shop/walk-in counter sale. These orders
 *       need no delivery partner at all (neither in-house nor QuikShipX): see
 *       {@code OrderService#createSalespersonOrder}, which forces
 *       {@code deliveryMethod = IN_HOUSE} for this lead source so every
 *       existing {@code isInHouseDelivery()} gate (QuikShipX publish on
 *       create/approve, courier assignment on dispatch) is skipped for free.</li>
 * </ul>
 */
public enum LeadSource {
    WHATSAPP,
    INSTAGRAM,
    FACEBOOK,
    // GOOGLE is an original lead channel still present in persisted/seeded data
    // (V27). It was dropped from this enum at one point, which caused Hibernate
    // @Enumerated(STRING) to throw "No enum constant ...LeadSource.GOOGLE" and
    // 500 any order list that included such a row. Kept here so historical
    // "Google" leads map correctly instead of being rewritten to OTHER.
    GOOGLE,
    OFFLINE,
    SHOPIFY_UPSELL,
    SHOPIFY_ABANDONMENT_SALE,
    INBOUND_CALLS,
    REPEAT_CUSTOMER,
    REFERRAL,
    OTHER,
    SHOPIFY,
    COUNTER_SALE
}
