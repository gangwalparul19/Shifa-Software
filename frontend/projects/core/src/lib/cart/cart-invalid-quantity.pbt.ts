import * as fc from 'fast-check';
import {
  CartLine,
  MAX_QUANTITY,
  addToCart,
  isValidQuantity,
  setCartQuantity,
} from './cart-logic';

/**
 * Feature: shifa-herbal-remedies, Property 14: Invalid cart quantities are
 * rejected without side effects. For any cart and any quantity that is less
 * than 1, greater than 999, or not a whole number, both adding a product with
 * that quantity and setting a line to that quantity are rejected, and the cart
 * contents are left exactly unchanged.
 *
 * Validates: Requirements 2.6
 */
describe('cart-logic invalid quantities (PBT)', () => {
  const money = (paise: number): string => (paise / 100).toFixed(2);

  const line: fc.Arbitrary<CartLine> = fc.record({
    productId: fc.integer({ min: 1, max: 20 }),
    paise: fc.integer({ min: 0, max: 500_000 }),
    quantity: fc.integer({ min: 1, max: MAX_QUANTITY }),
  }).map((r) => ({
    productId: r.productId,
    salePrice: money(r.paise),
    quantity: r.quantity,
  }));

  // Distinct product lines so the cart is well-formed.
  const cart = fc
    .array(line, { maxLength: 10 })
    .map((lines) => {
      const seen = new Set<number>();
      return lines.filter((l) => (seen.has(l.productId) ? false : seen.add(l.productId)));
    });

  // Quantities that must be rejected: <1, >999, or non-integer / non-finite.
  const invalidQuantity: fc.Arbitrary<number> = fc.oneof(
    fc.integer({ min: -1000, max: 0 }),
    fc.integer({ min: MAX_QUANTITY + 1, max: 100_000 }),
    fc.double({ min: 1, max: 999, noNaN: true }).filter((n) => !Number.isInteger(n)),
    fc.constantFrom(Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, 1.5, 0.5),
  );

  // Feature: shifa-herbal-remedies, Property 14: Invalid cart quantities are rejected without side effects
  it('rejects an add with an invalid quantity and leaves the cart unchanged', () => {
    fc.assert(
      fc.property(
        cart,
        fc.integer({ min: 1, max: 30 }),
        fc.integer({ min: 0, max: 500_000 }),
        invalidQuantity,
        (lines, productId, paise, quantity) => {
          fc.pre(!isValidQuantity(quantity));
          const before = structuredClone(lines);
          const result = addToCart(lines, {
            productId,
            salePrice: money(paise),
            quantity,
          });
          expect(result.ok).toBe(false);
          expect(result.message).toBeDefined();
          expect(result.lines).toEqual(before);
          // The original array reference is never mutated in place.
          expect(lines).toEqual(before);
        },
      ),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 14: Invalid cart quantities are rejected without side effects
  it('rejects a set-quantity with an invalid value and leaves the cart unchanged', () => {
    fc.assert(
      fc.property(cart, invalidQuantity, (lines, quantity) => {
        fc.pre(!isValidQuantity(quantity));
        fc.pre(lines.length > 0);
        const before = structuredClone(lines);
        const targetId = lines[0].productId;
        const result = setCartQuantity(lines, targetId, quantity);
        expect(result.ok).toBe(false);
        expect(result.message).toBeDefined();
        expect(result.lines).toEqual(before);
        expect(lines).toEqual(before);
      }),
      { numRuns: 200 },
    );
  });
});
