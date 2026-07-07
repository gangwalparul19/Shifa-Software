/**
 * Returns / refunds / RTO workflow ("operations depth" Feature 1).
 *
 * <p>Provides a first-class {@link com.shifa.oms.returns.OrderReturn} record
 * against an order with its own lifecycle
 * ({@link com.shifa.oms.returns.ReturnStatus}: REQUESTED &rarr; APPROVED &rarr;
 * REFUNDED, or REJECTED). Previously RTO was only an order lifecycle status with
 * no return/refund tracking. On approval the order's line items may be returned
 * to stock via the inventory module; every mutation is best-effort audited.
 */
package com.shifa.oms.returns;
