import { Money } from '../models/money.model';
import { paiseToMoney, toPaise } from '../money/money.util';

/** Minimum / maximum quantity for any single cart line (Req 2.1, 2.6, 2.7). */
export const MIN_QUANTITY = 1;
export const MAX_QUANTITY = 999;

/** Validation message shown when a quantity is out of range (Req 2.6). */
export const QUANTITY_RANGE_MESSAGE = `Quantity must be a whole number between ${MIN_QUANTITY} and ${MAX_QUANTITY}.`;

/**
 * The minimal shape the pure cart logic needs from a cart line: a product
 * identity, its unit sale price (fixed-scale decimal string), and a quantity.
 * Callers (e.g. the storefront {@code CartService}) use richer line types with
 * product snapshots; the generic functions below preserve those extra fields.
 */
export interface CartLine {
  productId: number;
  salePrice: Money;
  quantity: number;
}

/** Result of a pure cart mutation; {@link lines} is unchanged when {@link ok} is false. */
export interface CartMutation<T extends CartLine> {
  ok: boolean;
  lines: T[];
  message?: string;
}

/** True when `value` is a whole number within [MIN_QUANTITY, MAX_QUANTITY]. */
export function isValidQuantity(value: number): boolean {
  return Number.isInteger(value) && value >= MIN_QUANTITY && value <= MAX_QUANTITY;
}

/**
 * Adds `candidate` to the cart (Req 2.1, 2.7). If a line for the same product
 * already exists, its quantity is increased by {@code candidate.quantity} and
 * capped at {@link MAX_QUANTITY} rather than creating a duplicate line. A
 * candidate whose quantity is not a whole number in 1..999 is rejected and the
 * cart is returned unchanged (Req 2.6).
 */
export function addToCart<T extends CartLine>(
  lines: readonly T[],
  candidate: T,
): CartMutation<T> {
  if (!isValidQuantity(candidate.quantity)) {
    return { ok: false, lines: [...lines], message: QUANTITY_RANGE_MESSAGE };
  }
  const index = lines.findIndex((line) => line.productId === candidate.productId);
  if (index >= 0) {
    const merged = Math.min(lines[index].quantity + candidate.quantity, MAX_QUANTITY);
    const next = lines.map((line, i) =>
      i === index ? { ...line, quantity: merged } : line,
    );
    return { ok: true, lines: next };
  }
  return { ok: true, lines: [...lines, candidate] };
}

/**
 * Sets an existing line's quantity to `quantity` (Req 2.2). A non-whole or
 * out-of-range value is rejected and the cart is left unchanged (Req 2.6).
 */
export function setCartQuantity<T extends CartLine>(
  lines: readonly T[],
  productId: number,
  quantity: number,
): CartMutation<T> {
  if (!isValidQuantity(quantity)) {
    return { ok: false, lines: [...lines], message: QUANTITY_RANGE_MESSAGE };
  }
  const next = lines.map((line) =>
    line.productId === productId ? { ...line, quantity } : line,
  );
  return { ok: true, lines: next };
}

/** Removes a line for `productId`, returning the remaining lines (Req 2.3). */
export function removeFromCart<T extends CartLine>(
  lines: readonly T[],
  productId: number,
): T[] {
  return lines.filter((line) => line.productId !== productId);
}

/** Total number of units across all lines — drives the cart badge (Req 2.1, 2.3). */
export function cartItemCount(lines: readonly CartLine[]): number {
  return lines.reduce((sum, line) => sum + line.quantity, 0);
}

/** Number of distinct product lines in the cart. */
export function cartLineCount(lines: readonly CartLine[]): number {
  return lines.length;
}

/** Cart subtotal in integer paise: Σ salePrice × quantity, exact (Req 2.2). */
export function cartSubtotalPaise(lines: readonly CartLine[]): number {
  return lines.reduce((sum, line) => sum + toPaise(line.salePrice) * line.quantity, 0);
}

/** Cart subtotal as a fixed-scale Money string (Req 2.2). */
export function cartSubtotal(lines: readonly CartLine[]): Money {
  return paiseToMoney(cartSubtotalPaise(lines));
}
