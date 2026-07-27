import * as fc from 'fast-check';
import { OrderStatus, PaymentStatus } from './order.model';
import { ReceivableType } from './receivable.model';
import { ProductVisibility } from './product.model';

/**
 * Feature: shifa-herbal-remedies
 *
 * Harness smoke test verifying the Jest + fast-check property-based testing
 * setup for the shared `core` library, exercised against the typed domain
 * enums. Numbered correctness properties from the design (cart, payment/COD,
 * checkout validation) are implemented in their dedicated tasks.
 */
describe('core model enums (PBT harness)', () => {
  const orderStatuses = Object.values(OrderStatus);
  const paymentStatuses = Object.values(PaymentStatus);

  it('OrderStatus defines exactly the 18 lifecycle states', () => {
    expect(orderStatuses).toHaveLength(18);
    expect(new Set(orderStatuses).size).toBe(18);
  });

  it('every OrderStatus value equals its backend enum name (UPPER_SNAKE wire format)', () => {
    // The values must match the backend enum name() Jackson serialises, so status
    // comparisons (colours, return eligibility, grouping) line up with API data.
    for (const value of orderStatuses) {
      expect(value).toMatch(/^[A-Z0-9_]+$/);
    }
    expect(OrderStatus.PENDING_ADMIN_APPROVAL).toBe('PENDING_ADMIN_APPROVAL');
    expect(OrderStatus.COD_COLLECTED).toBe('COD_COLLECTED');
  });

  it('PaymentStatus defines exactly FULLY_PAID / PARTIALLY_PAID / COD', () => {
    expect(new Set(paymentStatuses)).toEqual(
      new Set(['FULLY_PAID', 'PARTIALLY_PAID', 'COD']),
    );
  });

  it('every drawn enum value is a valid, non-empty member of its set', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...orderStatuses),
        fc.constantFrom(...paymentStatuses),
        fc.constantFrom(...Object.values(ReceivableType)),
        fc.constantFrom(...Object.values(ProductVisibility)),
        (orderStatus, paymentStatus, receivableType, visibility) => {
          expect(orderStatus.length).toBeGreaterThan(0);
          expect(orderStatuses).toContain(orderStatus);
          expect(paymentStatuses).toContain(paymentStatus);
          expect(Object.values(ReceivableType)).toContain(receivableType);
          expect(Object.values(ProductVisibility)).toContain(visibility);
        },
      ),
      { numRuns: 100 },
    );
  });
});
