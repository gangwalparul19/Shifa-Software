import { Money } from '../models/money.model';

/**
 * Decimal-safe helpers for the {@link Money} type (fixed-scale decimal strings
 * mirroring the backend's {@code DECIMAL(12,2)} columns).
 *
 * <p>Money is carried as a string to avoid floating-point drift. Cart/pricing
 * math works in integer paise (1/100 rupee) and only formats to a display
 * string at the edges, so subtotals stay exact. These functions are pure so
 * they can be property-tested in isolation.
 */

/** Parses a Money string (or number) to an integer number of paise. Invalid -> 0. */
export function toPaise(value: Money | number | null | undefined): number {
  if (value === null || value === undefined) {
    return 0;
  }
  const num = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(num)) {
    return 0;
  }
  return Math.round(num * 100);
}

/** Formats an integer number of paise back to a Money string (e.g. "1499.00"). */
export function paiseToMoney(paise: number): Money {
  const sign = paise < 0 ? '-' : '';
  const abs = Math.abs(Math.trunc(paise));
  const rupees = Math.floor(abs / 100);
  const cents = abs % 100;
  return `${sign}${rupees}.${cents.toString().padStart(2, '0')}`;
}
