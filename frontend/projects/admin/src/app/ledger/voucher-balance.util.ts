/**
 * Pure, framework-free client-side double-entry balance helper for the voucher
 * entry form (Req 5.2). It mirrors the server's `DoubleEntry` rule: a voucher is
 * balanced when the sum of its debit amounts equals the sum of its credit
 * amounts.
 *
 * Kept deliberately pure and side-effect free so it can be reasoned about and
 * property-tested in isolation (task 13.4): the same helper drives the live
 * Dr/Cr balance indicator and the submit-gating in `VoucherEntryComponent`.
 *
 * Money is compared in integer paise (amount × 100, rounded HALF-ish via
 * `Math.round`) so that ordinary decimal-rupee inputs (e.g. `0.1 + 0.2`) do not
 * drift on IEEE-754 floating point — matching the backend's `BigDecimal`
 * scale-2 arithmetic where two amounts are equal iff their paise are equal.
 */

import { DrCr } from './ledger.model';

/** One Dr/Cr posting line as far as balancing is concerned. */
export interface BalanceLine {
  /** The debit or credit side of the posting. */
  side: DrCr;
  /** The line amount in rupees. Non-finite / non-positive values contribute 0. */
  amount: number | null | undefined;
}

/** The result of balancing a set of voucher lines. */
export interface VoucherBalance {
  /** Sum of all debit-line amounts, in rupees (2-decimal precise). */
  debitTotal: number;
  /** Sum of all credit-line amounts, in rupees (2-decimal precise). */
  creditTotal: number;
  /** `debitTotal − creditTotal`, in rupees; zero exactly when balanced. */
  difference: number;
  /** True when the debit total equals the credit total (to the paise). */
  balanced: boolean;
}

/** Converts a rupee amount to whole paise, treating invalid/negative as 0. */
function toPaise(amount: number | null | undefined): number {
  const n = Number(amount);
  if (!Number.isFinite(n) || n <= 0) {
    return 0;
  }
  return Math.round(n * 100);
}

/** Formats whole paise back to a 2-decimal rupee number. */
function toRupees(paise: number): number {
  return paise / 100;
}

/**
 * Computes the debit/credit totals, their difference, and whether the lines
 * balance. An empty set of lines is trivially balanced (0 == 0); the caller
 * (and the server) separately require at least two lines to post (Req 5.1).
 */
export function balanceOf(lines: readonly BalanceLine[]): VoucherBalance {
  let debitPaise = 0;
  let creditPaise = 0;
  for (const line of lines) {
    const paise = toPaise(line.amount);
    if (line.side === 'DEBIT') {
      debitPaise += paise;
    } else if (line.side === 'CREDIT') {
      creditPaise += paise;
    }
  }
  const diffPaise = debitPaise - creditPaise;
  return {
    debitTotal: toRupees(debitPaise),
    creditTotal: toRupees(creditPaise),
    difference: toRupees(diffPaise),
    balanced: diffPaise === 0,
  };
}

/**
 * True when the voucher lines are balanced — sum of debits equals sum of
 * credits (Req 5.2). Convenience wrapper over {@link balanceOf}.
 */
export function isVoucherBalanced(lines: readonly BalanceLine[]): boolean {
  return balanceOf(lines).balanced;
}
