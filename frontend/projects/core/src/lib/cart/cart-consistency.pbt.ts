import * as fc from 'fast-check';
import { toPaise } from '../money/money.util';
import {
  CartLine,
  MAX_QUANTITY,
  addToCart,
  cartItemCount,
  cartSubtotalPaise,
  removeFromCart,
  setCartQuantity,
} from './cart-logic';

/**
 * Feature: shifa-herbal-remedies, Property 12: Cart consistency. For any
 * sequence of cart operations (add with a valid quantity, change quantity,
 * remove), the cart holds at most one line per product (adding an existing
 * product merges into that line, capped at 999), the displayed item count
 * equals the sum of line quantities, and the subtotal equals the sum over all
 * lines of salePrice × quantity computed with exact decimal arithmetic.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.7
 */
describe('cart-logic consistency (PBT)', () => {
  // Prices as exact fixed-scale decimals: draw integer paise, format to Money.
  const pricedProduct = fc.record({
    productId: fc.integer({ min: 1, max: 12 }),
    paise: fc.integer({ min: 0, max: 500_000 }),
  });

  type Op =
    | { kind: 'add'; productId: number; paise: number; quantity: number }
    | { kind: 'set'; productId: number; quantity: number }
    | { kind: 'remove'; productId: number };

  const opArb: fc.Arbitrary<Op> = fc.oneof(
    fc.record({
      kind: fc.constant('add' as const),
      productId: fc.integer({ min: 1, max: 12 }),
      paise: fc.integer({ min: 0, max: 500_000 }),
      quantity: fc.integer({ min: 1, max: MAX_QUANTITY }),
    }),
    fc.record({
      kind: fc.constant('set' as const),
      productId: fc.integer({ min: 1, max: 12 }),
      quantity: fc.integer({ min: 1, max: MAX_QUANTITY }),
    }),
    fc.record({
      kind: fc.constant('remove' as const),
      productId: fc.integer({ min: 1, max: 12 }),
    }),
  );

  const money = (paise: number): string => (paise / 100).toFixed(2);

  // Feature: shifa-herbal-remedies, Property 12: Cart consistency
  it('keeps count, subtotal, and single-line-per-product invariants', () => {
    fc.assert(
      fc.property(fc.array(opArb, { maxLength: 40 }), (ops) => {
        let lines: CartLine[] = [];
        // Reference model: productId -> { paise (unit), quantity }.
        const ref = new Map<number, { paise: number; quantity: number }>();

        for (const op of ops) {
          if (op.kind === 'add') {
            const candidate: CartLine = {
              productId: op.productId,
              salePrice: money(op.paise),
              quantity: op.quantity,
            };
            const result = addToCart(lines, candidate);
            expect(result.ok).toBe(true);
            lines = result.lines;

            const existing = ref.get(op.productId);
            if (existing) {
              existing.quantity = Math.min(existing.quantity + op.quantity, MAX_QUANTITY);
            } else {
              ref.set(op.productId, { paise: op.paise, quantity: op.quantity });
            }
          } else if (op.kind === 'set') {
            const result = setCartQuantity(lines, op.productId, op.quantity);
            expect(result.ok).toBe(true);
            lines = result.lines;
            const existing = ref.get(op.productId);
            if (existing) {
              existing.quantity = op.quantity;
            }
          } else {
            lines = removeFromCart(lines, op.productId);
            ref.delete(op.productId);
          }

          // Invariant: at most one line per product (Req 2.7).
          const ids = lines.map((l) => l.productId);
          expect(new Set(ids).size).toBe(ids.length);

          // Invariant: displayed count = Σ quantity (Req 2.1, 2.3).
          const expectedCount = [...ref.values()].reduce((s, v) => s + v.quantity, 0);
          expect(cartItemCount(lines)).toBe(expectedCount);

          // Invariant: subtotal = Σ unit × qty, exact in paise (Req 2.2).
          const expectedPaise = [...ref.values()].reduce(
            (s, v) => s + v.paise * v.quantity,
            0,
          );
          expect(cartSubtotalPaise(lines)).toBe(expectedPaise);

          // Every line's quantity is in range and its price round-trips exactly.
          for (const line of lines) {
            expect(line.quantity).toBeGreaterThanOrEqual(1);
            expect(line.quantity).toBeLessThanOrEqual(MAX_QUANTITY);
            expect(toPaise(line.salePrice)).toBe(ref.get(line.productId)!.paise);
          }
        }
      }),
      { numRuns: 200 },
    );
  });
});
