/**
 * Config-light delivery / shipping estimate shown on the product detail and
 * checkout pages. The day range lives here as a single constant so it's trivial
 * to change later; this is intentionally static — it is not a courier
 * serviceability lookup.
 */

/**
 * Cart subtotal (in rupees) at or above which shipping is free. Drives the
 * cart-drawer "you're ₹X away from FREE shipping" progress bar. Static by
 * design — a single knob to tweak later.
 */
export const FREE_SHIPPING_THRESHOLD = 499;

/** Earliest business-day estimate for pan-India delivery. */
export const DELIVERY_MIN_DAYS = 3;

/** Latest business-day estimate for pan-India delivery. */
export const DELIVERY_MAX_DAYS = 7;

/** The bare "3–7 business days" range, e.g. for inline copy. */
export const DELIVERY_RANGE = `${DELIVERY_MIN_DAYS}–${DELIVERY_MAX_DAYS} business days`;

/**
 * A generic, always-safe delivery estimate string. When a destination city is
 * known (e.g. resolved from a checkout pincode) it is woven into the message,
 * otherwise a pan-India message is returned.
 */
export function deliveryEstimate(city?: string | null): string {
  const place = (city ?? '').trim();
  if (place) {
    return `Delivery to ${place} in ${DELIVERY_RANGE}`;
  }
  return `Delivery in ${DELIVERY_RANGE} across India`;
}
