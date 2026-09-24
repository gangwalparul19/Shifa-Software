package com.shifa.oms.gst.domain;

/**
 * GSTR-1 outward-supply document classification (GST filing compliance, Req 1.4–1.7):
 * <ul>
 *   <li>{@link #B2B} — supply to a GST-registered buyer (a valid buyer GSTIN is present);
 *       reported invoice-level in GSTR-1.</li>
 *   <li>{@link #B2CL} — B2C Large: an <strong>inter-state</strong> supply to an unregistered
 *       buyer with invoice value greater than ₹2,50,000; reported invoice-level.</li>
 *   <li>{@link #B2CS} — B2C Small: any other unregistered-buyer supply; reported summarised
 *       (rate + place-of-supply).</li>
 *   <li>{@link #EXPORT} — a supply shipped OUTSIDE India (GSTR-1 Table 6A, exports).
 *       The business does not file a LUT, so these are taxable exports charged at
 *       18% IGST (WPAY — with payment of tax), reported separately, never as
 *       B2CS/B2CL.</li>
 * </ul>
 *
 * <p>{@code B2C = {B2CL, B2CS}} — the two unregistered-buyer categories.
 */
public enum DocumentCategory {
    B2B,
    B2CL,
    B2CS,
    EXPORT
}
