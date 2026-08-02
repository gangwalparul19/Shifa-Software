package com.shifa.oms.integration.shopify;

/**
 * Why an ingested Shopify order needs a human look (Req 3.4, 3.5, 3.7, 3.9, 3.11).
 *
 * <p>The guiding rule: an order the store has already taken is <b>always</b> created, never
 * rejected. Losing a real order because a SKU did not match would be far worse than
 * recording an imperfect one and flagging it. Each reason names precisely what a human has
 * to fix.
 */
public enum ReviewReason {

    /** A line item's SKU matched no Shifa product, or matched more than one (Req 3.4). */
    UNMAPPED_SKU("A line item's SKU did not match exactly one product, so it has no product link."),

    /** No usable 10-digit contact number could be derived (Req 3.5). */
    MISSING_CONTACT("The customer's contact number could not be read as a 10-digit number."),

    /** The line items do not add up to the Shopify total (Req 3.7). */
    TOTAL_MISMATCH("The line item amounts do not add up to the Shopify order total."),

    /** Part of the shipping address is absent (Req 3.11). */
    INCOMPLETE_ADDRESS("The shipping address is missing one or more parts.");

    private final String description;

    ReviewReason(String description) {
        this.description = description;
    }

    /** A human-readable explanation, shown in the admin review queue. */
    public String description() {
        return description;
    }
}
