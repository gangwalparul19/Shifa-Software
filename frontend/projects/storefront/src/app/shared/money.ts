import { Money, paiseToMoney, toPaise } from 'core';

/**
 * Storefront money display helpers.
 *
 * <p>Decimal-safe parsing/formatting ({@link toPaise} / {@link paiseToMoney})
 * lives in the shared {@code core} library so the cart math has a single source
 * of truth and can be property-tested in isolation. They are re-exported here
 * for existing storefront imports; INR display formatting stays storefront-side.
 */
export { toPaise, paiseToMoney };

/** Formats a Money value (or paise-derived amount) for display, e.g. "₹1,499". */
export function formatInr(value: Money | number | null | undefined): string {
  const paise = toPaise(value);
  const rupees = paise / 100;
  const whole = Number.isInteger(rupees);
  const formatted = rupees.toLocaleString('en-IN', {
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: 2,
  });
  return `₹${formatted}`;
}

/** Whole-number discount percentage from MRP to sale price (0 when none). */
export function discountPercent(
  mrp: Money | number | null | undefined,
  salePrice: Money | number | null | undefined,
): number {
  const mrpPaise = toPaise(mrp);
  const salePaise = toPaise(salePrice);
  if (mrpPaise <= 0 || salePaise <= 0 || salePaise >= mrpPaise) {
    return 0;
  }
  return Math.round(((mrpPaise - salePaise) / mrpPaise) * 100);
}
