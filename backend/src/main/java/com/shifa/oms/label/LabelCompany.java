package com.shifa.oms.label;

/**
 * Company / seller details printed on the shipping-style label (brand name +
 * logo + GST No + address in the header, seller name and pickup-and-return
 * address in the shipment grid). Sourced from the app settings by
 * {@link LabelService} and passed into the pure {@link LabelContentBuilder}; any
 * field may be {@code null}/blank, in which case the renderer omits or falls back
 * gracefully.
 *
 * @param brandName           the brand shown in the header (e.g. "Shifa Herbal Remedies")
 * @param sellerName          the seller/legal name shown in the grid
 * @param pickupReturnAddress the one-line pickup &amp; return address shown in the grid
 * @param sellerGstin         the seller GST No shown under the seller address in the header
 * @param sellerAddress       the one-line seller address shown in the header (under the brand name)
 */
public record LabelCompany(String brandName, String sellerName, String pickupReturnAddress,
                           String sellerGstin, String sellerAddress) {

    /** Back-compat constructor without GST No / seller address (tests, defaults). */
    public LabelCompany(String brandName, String sellerName, String pickupReturnAddress) {
        this(brandName, sellerName, pickupReturnAddress, null, null);
    }

    /** A safe default (brand only) used when no settings are available (e.g. in tests). */
    public static LabelCompany defaults() {
        return new LabelCompany("Shifa Herbal Remedies", null, null, null, null);
    }
}
