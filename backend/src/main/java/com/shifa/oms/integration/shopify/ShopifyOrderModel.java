package com.shifa.oms.integration.shopify;

import java.math.BigDecimal;
import java.util.List;

/**
 * A Shopify order, as much of it as Shifa OMS needs, as a pure model.
 *
 * <p>Only the fields Shifa actually uses are modelled. Shopify order payloads carry well
 * over a hundred fields; mirroring them would create a second, competing definition of an
 * order and a large surface to keep in step with their API version.
 *
 * @param shopifyOrderId     Shopify's numeric order id; the ingestion idempotency key
 * @param shopifyOrderNumber the human-facing order name, e.g. {@code "#1042"}
 * @param customerName       the buyer's full name
 * @param contactNumber      the contact number exactly as Shopify supplied it, unnormalised
 * @param email              the buyer's email, when present
 * @param address            the shipping address
 * @param lineItems          the ordered items, in Shopify's order
 * @param totalPrice         the order total as Shopify calculated it. Persisted verbatim and
 *                           never recomputed from line items, so Shifa's reports reconcile
 *                           with the store even when discounts or shipping do not add up
 *                           (Req 3.6)
 * @param currency           the order currency, for a sanity check against the shop's
 * @param financialStatus    Shopify's payment state, e.g. {@code "paid"}, {@code "pending"}
 */
public record ShopifyOrderModel(
        String shopifyOrderId,
        String shopifyOrderNumber,
        String customerName,
        String contactNumber,
        String email,
        Address address,
        List<LineItem> lineItems,
        BigDecimal totalPrice,
        String currency,
        String financialStatus) {

    public ShopifyOrderModel {
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
        totalPrice = totalPrice == null ? BigDecimal.ZERO : totalPrice;
    }

    /**
     * A shipping address. Every part is nullable because Shopify does not require them all,
     * and the Shifa columns are NOT NULL, so absent parts become empty strings plus an
     * {@code INCOMPLETE_ADDRESS} review reason rather than a failed ingestion.
     */
    public record Address(
            String addressLine,
            String city,
            String state,
            String postalCode,
            String country) {

        /** Whether every part Shifa needs is present. */
        public boolean isComplete() {
            return present(addressLine) && present(city) && present(state) && present(postalCode);
        }

        private static boolean present(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * One ordered item.
     *
     * @param sku       the SKU used to match a Shifa product; may be absent
     * @param name      the item name as shown to the buyer
     * @param quantity  units ordered
     * @param unitPrice the per-unit price
     */
    public record LineItem(String sku, String name, int quantity, BigDecimal unitPrice) {

        public LineItem {
            quantity = Math.max(1, quantity);
            unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }

        /** The extended amount for this line. */
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    /** The sum of the line amounts, for the total-mismatch check (Req 3.7). */
    public BigDecimal lineItemsTotal() {
        return lineItems.stream()
                .map(LineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Whether the line amounts agree with Shopify's total to within a rupee (Req 3.7).
     * A tolerance rather than equality, because Shopify applies discounts, shipping and
     * rounding that Shifa does not reproduce.
     */
    public boolean totalsAgree() {
        return totalPrice.subtract(lineItemsTotal()).abs().compareTo(BigDecimal.ONE) <= 0;
    }
}
