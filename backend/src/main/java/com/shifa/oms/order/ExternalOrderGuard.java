package com.shifa.oms.order;

import com.shifa.oms.common.ExternalOrderReadOnlyException;

/**
 * Enforces that an order owned by an external storefront is read-only in Shifa OMS
 * (spec {@code shopify-quikshipx-order-sync}, Req 9.4, 9.5).
 *
 * <p>A Shopify order's content — customer, address, items, money — is Shopify's to
 * change. Editing it here would make the two systems disagree about what was sold, and
 * Shopify's version is the one the customer saw and paid against. Cancelling likewise has
 * to happen where the order was placed, so the refund and the customer email follow.
 *
 * <p>Pure and static: no Spring, so any service can call it without a new dependency, and
 * the rule is stated in exactly one place rather than re-derived per call site.
 *
 * <p>Deliberately throws 409 rather than 403. The caller is not lacking permission — no
 * role has this permission — the order is simply in a state where the change belongs
 * elsewhere.
 */
public final class ExternalOrderGuard {

    private ExternalOrderGuard() {
        // Pure static helper.
    }

    /**
     * Rejects a change to an externally-owned order's content.
     *
     * <p>Call this at the top of any future order-edit path. There is no order-content
     * edit endpoint today, which is why this is the single enforcement point rather than
     * a scattering of checks.
     */
    public static void requireContentMutable(OrderEntity order) {
        if (isExternallyOwned(order)) {
            throw new ExternalOrderReadOnlyException(
                    "Order " + order.getOrderCode() + " was placed on Shopify, which is the system "
                            + "of record for its customer, items and amounts. Edit it in Shopify.");
        }
    }

    /**
     * Rejects a cancellation of an externally-owned order by anyone other than the
     * automatic actor (Req 9.5).
     *
     * @param actor the requesting actor; the SYSTEM actor is permitted, because a
     *              cancellation mirrored back from the storefront is exactly how a
     *              Shopify cancellation should reach Shifa
     */
    public static void requireCancellable(OrderEntity order, Actor actor) {
        if (isExternallyOwned(order) && (actor == null || !actor.isSystem())) {
            throw new ExternalOrderReadOnlyException(
                    "Order " + order.getOrderCode() + " was placed on Shopify, so it must be "
                            + "cancelled there; the cancellation then flows back into Shifa.");
        }
    }

    /** Whether an external storefront owns this order's content. */
    public static boolean isExternallyOwned(OrderEntity order) {
        return order != null && order.getSource() != null && order.getSource().isShopify();
    }
}
