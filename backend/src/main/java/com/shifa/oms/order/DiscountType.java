package com.shifa.oms.order;

/**
 * How a salesperson-entered order discount is expressed (price-list feature).
 *
 * <p>Order prices are GST-inclusive, so the discount is applied to the
 * GST-inclusive subtotal and the resulting payable total remains GST-inclusive.
 */
public enum DiscountType {

    /** A flat rupee amount off the subtotal (e.g. ₹100 off). */
    FLAT,

    /** A percentage off the subtotal (0–100), e.g. 10% off. */
    PERCENT
}
