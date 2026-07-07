import * as fc from 'fast-check';
import { CartLine, MAX_QUANTITY, cartItemCount } from '../cart/cart-logic';
import {
  WishlistEntry,
  addToWishlist,
  isInWishlist,
  moveWishlistItemToCart,
  removeFromWishlist,
} from './wishlist-logic';

/**
 * Feature: shifa-herbal-remedies, Property 13: Wishlist set semantics and
 * move-to-cart. For any sequence of wishlist adds/removes, a product appears at
 * most once (a duplicate add is a no-op); and moving a saved product to the
 * cart adds it to the cart with a quantity of at least 1 and removes it from the
 * wishlist.
 *
 * Validates: Requirements 2.4, 2.5, 2.8
 */
describe('wishlist-logic set semantics (PBT)', () => {
  interface Entry extends WishlistEntry {
    productId: number;
    salePrice: string;
  }

  const money = (paise: number): string => (paise / 100).toFixed(2);

  const entryArb: fc.Arbitrary<Entry> = fc
    .record({ productId: fc.integer({ min: 1, max: 15 }), paise: fc.integer({ min: 0, max: 300_000 }) })
    .map((r) => ({ productId: r.productId, salePrice: money(r.paise) }));

  type Op = { kind: 'add'; entry: Entry } | { kind: 'remove'; productId: number };

  const opArb: fc.Arbitrary<Op> = fc.oneof(
    entryArb.map((entry) => ({ kind: 'add' as const, entry })),
    fc.integer({ min: 1, max: 15 }).map((productId) => ({ kind: 'remove' as const, productId })),
  );

  // Feature: shifa-herbal-remedies, Property 13: Wishlist set semantics and move-to-cart
  it('holds at most one entry per product across any add/remove sequence', () => {
    fc.assert(
      fc.property(fc.array(opArb, { maxLength: 40 }), (ops) => {
        let entries: Entry[] = [];
        const ref = new Set<number>();

        for (const op of ops) {
          if (op.kind === 'add') {
            const wasPresent = ref.has(op.entry.productId);
            const before = [...entries];
            entries = addToWishlist(entries, op.entry);
            if (wasPresent) {
              // Duplicate add is a no-op (Req 2.4, 2.8).
              expect(entries).toEqual(before);
            } else {
              ref.add(op.entry.productId);
            }
          } else {
            entries = removeFromWishlist(entries, op.productId);
            ref.delete(op.productId);
          }

          const ids = entries.map((e) => e.productId);
          expect(new Set(ids).size).toBe(ids.length);
          expect(new Set(ids)).toEqual(ref);
        }
      }),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 13: Wishlist set semantics and move-to-cart
  it('moves a saved product to the cart (qty >= 1) and drops it from the wishlist', () => {
    fc.assert(
      fc.property(
        fc.array(entryArb, { maxLength: 12 }),
        fc.array(entryArb, { maxLength: 8 }),
        fc.integer({ min: 1, max: 20 }),
        (rawWishlist, rawCartSeed, productId) => {
          // De-dup both stores by productId to keep them well-formed.
          const dedup = <T extends { productId: number }>(list: T[]): T[] => {
            const seen = new Set<number>();
            return list.filter((x) => (seen.has(x.productId) ? false : seen.add(x.productId)));
          };
          const wishlist = dedup(rawWishlist);
          const cart: CartLine[] = dedup(rawCartSeed).map((e) => ({
            productId: e.productId,
            salePrice: e.salePrice,
            quantity: 1,
          }));

          const result = moveWishlistItemToCart(wishlist, cart, productId, (entry) => ({
            productId: entry.productId,
            salePrice: entry.salePrice,
            quantity: 1,
          }));

          if (!isInWishlist(wishlist, productId)) {
            // Not saved -> nothing moves.
            expect(result.moved).toBe(false);
            expect(result.wishlist).toEqual(wishlist);
            expect(result.cart).toEqual(cart);
            return;
          }

          expect(result.moved).toBe(true);
          // Removed from the wishlist (Req 2.5).
          expect(isInWishlist(result.wishlist, productId)).toBe(false);
          expect(result.wishlist.length).toBe(wishlist.length - 1);
          // Present in the cart with quantity >= 1 (Req 2.5).
          const line = result.cart.find((l) => l.productId === productId);
          expect(line).toBeDefined();
          expect(line!.quantity).toBeGreaterThanOrEqual(1);
          expect(line!.quantity).toBeLessThanOrEqual(MAX_QUANTITY);
          // Cart total units never decreases by a move.
          expect(cartItemCount(result.cart)).toBeGreaterThanOrEqual(cartItemCount(cart));
        },
      ),
      { numRuns: 200 },
    );
  });
});
