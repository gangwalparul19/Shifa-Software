package com.shifa.oms.label;

/**
 * Company / seller details printed on the shipping-style label (brand name in the
 * header, seller name and pickup-and-return address in the shipment grid). Sourced
 * from the app settings by {@link LabelService} and passed into the pure
 * {@link LabelContentBuilder}; any field may be {@code null}/blank, in which case
 * the renderer omits or falls back gracefully.
 *
 * @param brandName           the brand shown in the header bar (e.g. "Shifa Herbal Remedies")
 * @param sellerName          the seller/legal name shown in the grid
 * @param pickupReturnAddress the one-line pickup &amp; return address shown in the grid
 */
public record LabelCompany(String brandName, String sellerName, String pickupReturnAddress) {

    /** A safe default (brand only) used when no settings are available (e.g. in tests). */
    public static LabelCompany defaults() {
        return new LabelCompany("Shifa Herbal Remedies", null, null);
    }
}
