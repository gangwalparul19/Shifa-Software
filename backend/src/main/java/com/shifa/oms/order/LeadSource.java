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
 *   <li>{@link #GOOGLE} — lead arrived through Google (search/ads).</li>
 *   <li>{@link #OFFLINE} — walk-in / phone / other offline channel.</li>
 *   <li>{@link #OTHER} — anything else; accepts an optional free-text note
 *       ({@code lead_source_note}, Req 4.5).</li>
 * </ul>
 */
public enum LeadSource {
    WHATSAPP,
    INSTAGRAM,
    FACEBOOK,
    GOOGLE,
    OFFLINE,
    OTHER
}
