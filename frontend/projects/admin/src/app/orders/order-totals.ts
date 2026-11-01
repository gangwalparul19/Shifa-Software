import { Product, toPaise } from 'core';
import { formatInr } from '../shared/inr.pipe';
import { OrderDiscountType } from './orders.model';

/**
 * Pure money maths shared by the New Order and Edit Order forms.
 *
 * <p>Amounts are integer paise (1/100 rupee) throughout, converted from the
 * form's rupee numbers with the core {@code toPaise} helper, so subtotals stay
 * exact. The order total is rounded to the nearest whole rupee to match what the
 * backend charges (product-audit §4.6; product-catalog-pricing-gst Req 6, 8).
 * Nothing here touches Angular, signals, or forms: the components feed these
 * functions the same inputs their computed signals used to read inline.
 */

/** The fields of an order line that drive its total. */
export interface PricedLine {
  readonly rate: number | null | undefined;
  readonly quantity: number | null | undefined;
}

/** Smallest amount collected upfront: ₹100 in paise (client policy, no ₹0/COD orders). */
export const MIN_UPFRONT_PAISE = 10000;

/** How an entered amount classifies the payment (drives the shortcut highlight and hint). */
export type PaymentKind = 'below' | 'partial' | 'full' | 'none';

/** An order line's allowed selling-price range plus its pack size. */
export interface PriceBand {
  min: number;
  max: number;
  wtMl: string | null;
}

/** Per-line totals in paise (rate × quantity), aligned to the item rows. */
export function lineTotalsPaise(lines: readonly PricedLine[]): number[] {
  return lines.map((it) => toPaise(it.rate ?? 0) * (it.quantity || 0));
}

/** The (unrounded) subtotal in paise: Σ line totals before any discount. */
export function subtotalPaiseOf(lineTotals: readonly number[]): number {
  return lineTotals.reduce((sum, cents) => sum + cents, 0);
}

/**
 * The order-level discount in paise (product-catalog-pricing-gst Req 6): PERCENT
 * of the subtotal or a FLAT amount, clamped to [0, subtotal].
 */
export function discountPaiseOf(
  subtotalPaise: number,
  discountType: '' | OrderDiscountType,
  discountValue: number,
): number {
  let d = 0;
  if (discountType === 'PERCENT') {
    const pct = Math.max(0, Math.min(100, discountValue || 0));
    d = Math.round((subtotalPaise * pct) / 100);
  } else if (discountType === 'FLAT') {
    d = toPaise(discountValue || 0);
  }
  return Math.max(0, Math.min(subtotalPaise, d));
}

/** The order total in paise = (subtotal − discount) rounded to the nearest whole rupee. */
export function orderTotalPaiseOf(subtotalPaise: number, discountPaise: number): number {
  const net = subtotalPaise - discountPaise;
  return Math.round(net / 100) * 100;
}

/** Remaining balance after the amount received (negative when overpaid). */
export function remainingPaiseOf(orderTotalPaise: number, receivedPaise: number): number {
  return orderTotalPaise - receivedPaise;
}

/**
 * Minimum amount (paise) that must be collected upfront: at least ₹100, or the
 * full total when the total is under ₹100 (a small order can't require more than
 * it costs). Mirrors the backend {@code requireMinimumUpfront}.
 */
export function minUpfrontPaiseOf(orderTotalPaise: number): number {
  return Math.min(MIN_UPFRONT_PAISE, orderTotalPaise);
}

/**
 * Classifies the entered amount: 'none' when there is no order total yet,
 * 'below' when under the minimum upfront (not a valid Full/Partial payment),
 * 'full' when the whole total is received, else 'partial'.
 */
export function paymentKindOf(
  orderTotalPaise: number,
  receivedPaise: number,
  minUpfrontPaise: number,
): PaymentKind {
  if (orderTotalPaise <= 0) {
    return 'none';
  }
  if (receivedPaise < minUpfrontPaise) {
    return 'below';
  }
  if (receivedPaise >= orderTotalPaise) {
    return 'full';
  }
  return 'partial';
}

/**
 * Whether the received amount is under the minimum-upfront policy. False while
 * there is no order total yet (nothing to validate).
 */
export function isBelowMinimumUpfront(
  orderTotalPaise: number,
  receivedPaise: number,
  minUpfrontPaise: number,
): boolean {
  if (orderTotalPaise <= 0) {
    return false;
  }
  return receivedPaise < minUpfrontPaise;
}

/** Clamps a one-tap payment amount (rupees) into [0, total] and rounds it to 2 decimals. */
export function clampReceivedRupees(amount: number, totalRupees: number): number {
  const safe = Math.max(0, Math.min(Number.isFinite(amount) ? amount : 0, totalRupees));
  return Number(safe.toFixed(2));
}

/** Formats a paise integer as a ₹ money string (whole paise only; fractions are truncated). */
export function formatPaise(paise: number): string {
  return formatInr(Math.trunc(paise) / 100, 2);
}

/**
 * The selling-price band for a product (product-catalog-pricing-gst Req 5.2,
 * 10.1). The floor falls back to the sale price when no explicit minimum is set.
 */
export function priceBandOf(
  product: Pick<Product, 'minimumRate' | 'salePrice' | 'mrp' | 'wtMl'>,
): PriceBand {
  const min = Number(product.minimumRate ?? product.salePrice);
  const max = Number(product.mrp);
  return { min, max, wtMl: product.wtMl ?? null };
}

/**
 * A validation message when a line's rate is outside its product's band (mirrors
 * the server rule so the salesperson sees it before submit), else null. A blank
 * or non-numeric rate, or a line with no band, is not reported here.
 */
export function rateBandError(
  rawRate: number | string | null | undefined,
  band: PriceBand | null,
): string | null {
  if (!band) {
    return null;
  }
  if (rawRate === null || rawRate === undefined || rawRate === '') {
    return null;
  }
  const rate = Number(rawRate);
  if (Number.isNaN(rate)) {
    return null;
  }
  if (rate < band.min) {
    return `Below minimum ${formatInr(band.min, 2)}`;
  }
  if (band.max && rate > band.max) {
    return `Above MRP ${formatInr(band.max, 2)}`;
  }
  return null;
}
