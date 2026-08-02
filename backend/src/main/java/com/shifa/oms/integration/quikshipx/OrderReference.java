package com.shifa.oms.integration.quikshipx;

import java.util.Optional;

/**
 * The QuikShipX order reference: the value Shifa OMS sends as
 * {@code customer_order_id} (contract: {@code docs/QUIKSHIPX-API-V1.md}).
 *
 * <p><b>Why this exists.</b> The client asked for Shopify-placed orders and
 * Shifa-punched orders to be distinguishable inside QuikShipX. The confirmed
 * create-order API has <b>no channel, source or tag field</b>, so there is nowhere
 * to put {@code SHIFA_ADMIN}. The one field we control is {@code customer_order_id},
 * documented as "Unique ID for the order from your system" — so the channel travels
 * as a prefix on it.
 *
 * <p>That gives the reference two jobs at once: it makes the channel visible in the
 * QuikShipX portal, and it is the correlation key that maps a QuikShipX shipment back
 * to a Shifa order. The second job matters more than the first, because the
 * create-order response is undocumented and may carry no shipment identifier at all,
 * leaving this the only reliable link between the two systems.
 *
 * <p>Pure and total: no Spring, no clock, no I/O.
 */
public final class OrderReference {

    private OrderReference() {
        // Pure static helper.
    }

    /**
     * Builds the reference for an order.
     *
     * <p>Deterministic in the order code, which is what makes it a safe idempotency
     * reference: the first submission and every retry send the identical value, so a
     * retry after an ambiguous timeout cannot create a second shipment (Req 5.3).
     *
     * @param prefix    the configured channel prefix, e.g. {@code "SHIFA-"}; null is treated as empty
     * @param orderCode the Shifa order code, e.g. {@code "SHR-1001"}
     * @throws IllegalArgumentException when the order code is absent or blank, since a
     *                                 blank reference would silently break correlation
     */
    public static String of(String prefix, String orderCode) {
        if (orderCode == null || orderCode.isBlank()) {
            throw new IllegalArgumentException("orderCode is required to build a QuikShipX order reference");
        }
        String safePrefix = prefix == null ? "" : prefix.trim();
        return safePrefix + orderCode.trim();
    }

    /**
     * Recovers the Shifa order code from a reference, for resolving an inbound status
     * event that carries an order reference but no shipment identifier (Req 6.15).
     *
     * <p>Returns empty when the reference does not carry the expected prefix, rather
     * than guessing: mis-resolving an event onto the wrong order would corrupt that
     * order's status history.
     */
    public static Optional<String> orderCodeFrom(String prefix, String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String trimmed = reference.trim();
        String safePrefix = prefix == null ? "" : prefix.trim();
        if (safePrefix.isEmpty()) {
            return Optional.of(trimmed);
        }
        if (trimmed.length() <= safePrefix.length()
                || !trimmed.regionMatches(true, 0, safePrefix, 0, safePrefix.length())) {
            return Optional.empty();
        }
        String code = trimmed.substring(safePrefix.length()).trim();
        return code.isEmpty() ? Optional.empty() : Optional.of(code);
    }

    /** Whether a reference carries the configured channel prefix. */
    public static boolean matchesPrefix(String prefix, String reference) {
        return orderCodeFrom(prefix, reference).isPresent();
    }
}
