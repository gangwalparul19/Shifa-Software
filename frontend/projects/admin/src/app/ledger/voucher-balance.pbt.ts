import * as fc from 'fast-check';
import { balanceOf, isVoucherBalanced, type BalanceLine } from './voucher-balance.util';
import type { DrCr } from './ledger.model';

/**
 * Feature: general-ledger-accounting
 * Property (client-side balance): the pure client-side Dr/Cr balance helper
 * matches the server's double-entry rule — a voucher is balanced iff the sum of
 * its debit amounts equals the sum of its credit amounts.
 *
 * **Validates: Requirements 5.2**
 *
 * The helper (`voucher-balance.util.ts`) drives the live balance indicator and
 * submit-gating in `VoucherEntryComponent`, mirroring the backend `DoubleEntry`
 * rule. To avoid IEEE-754 drift we generate amounts in whole rupees (the util
 * compares in integer paise), so an independent integer-paise oracle is exact.
 */
describe('voucher-balance helper (PBT) — Feature: general-ledger-accounting, Req 5.2', () => {
  const side: fc.Arbitrary<DrCr> = fc.constantFrom<DrCr>('DEBIT', 'CREDIT');

  // Positive whole-rupee amounts on exactly one of Dr/Cr — the input space the
  // voucher form produces (each posting line is a positive amount on one side).
  const line: fc.Arbitrary<BalanceLine> = fc.record({
    side,
    amount: fc.integer({ min: 1, max: 1_000_000 }),
  });

  const lines: fc.Arbitrary<BalanceLine[]> = fc.array(line, { minLength: 0, maxLength: 20 });

  /** Independent oracle: sum a side's amounts in integer paise, return rupees. */
  const oracleTotal = (ls: readonly BalanceLine[], want: DrCr): number => {
    let paise = 0;
    for (const l of ls) {
      if (l.side === want) {
        paise += Math.round(Number(l.amount) * 100);
      }
    }
    return paise / 100;
  };

  it('computes debit/credit totals equal to an independent paise oracle', () => {
    fc.assert(
      fc.property(lines, (ls) => {
        const b = balanceOf(ls);
        expect(b.debitTotal).toBe(oracleTotal(ls, 'DEBIT'));
        expect(b.creditTotal).toBe(oracleTotal(ls, 'CREDIT'));
        expect(b.difference).toBe(oracleTotal(ls, 'DEBIT') - oracleTotal(ls, 'CREDIT'));
      }),
      { numRuns: 200 },
    );
  });

  it('reports balanced iff sum of debits equals sum of credits (server double-entry rule)', () => {
    fc.assert(
      fc.property(lines, (ls) => {
        const debits = oracleTotal(ls, 'DEBIT');
        const credits = oracleTotal(ls, 'CREDIT');
        const expectedBalanced = debits === credits;
        expect(balanceOf(ls).balanced).toBe(expectedBalanced);
        expect(isVoucherBalanced(ls)).toBe(expectedBalanced);
      }),
      { numRuns: 200 },
    );
  });

  it('is always balanced when every amount appears once on each side (mirrored set)', () => {
    fc.assert(
      fc.property(fc.array(fc.integer({ min: 1, max: 1_000_000 }), { maxLength: 20 }), (amounts) => {
        const mirrored: BalanceLine[] = amounts.flatMap((amount) => [
          { side: 'DEBIT' as DrCr, amount },
          { side: 'CREDIT' as DrCr, amount },
        ]);
        const b = balanceOf(mirrored);
        expect(b.balanced).toBe(true);
        expect(b.difference).toBe(0);
        expect(b.debitTotal).toBe(b.creditTotal);
      }),
      { numRuns: 200 },
    );
  });

  it('is unbalanced when a single extra positive amount is added to one side', () => {
    fc.assert(
      fc.property(
        fc.array(fc.integer({ min: 1, max: 1_000_000 }), { maxLength: 20 }),
        fc.integer({ min: 1, max: 1_000_000 }),
        side,
        (amounts, extra, extraSide) => {
          const mirrored: BalanceLine[] = amounts.flatMap((amount) => [
            { side: 'DEBIT' as DrCr, amount },
            { side: 'CREDIT' as DrCr, amount },
          ]);
          const skewed: BalanceLine[] = [...mirrored, { side: extraSide, amount: extra }];
          expect(isVoucherBalanced(skewed)).toBe(false);
        },
      ),
      { numRuns: 200 },
    );
  });
});
