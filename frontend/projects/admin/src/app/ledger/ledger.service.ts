import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  AccountGroup,
  AccountGroupRequest,
  BalanceSheet,
  CashFlow,
  DayBook,
  FinancialYear,
  LedgerAccount,
  LedgerAccountRequest,
  LedgerStatement,
  OpeningBalance,
  OpeningBalanceRequest,
  OpeningBalances,
  PostVoucherRequest,
  ProfitAndLoss,
  TrialBalance,
  Voucher,
  VoucherAudit,
  VoucherType,
} from './ledger.model';

/** A reporting period — either a financial-year id or an explicit `from`/`to` range (Req 4.4). */
export interface LedgerPeriod {
  financialYearId?: number | null;
  from?: string | null;
  to?: string | null;
}

/**
 * Data access for the General Ledger ({@code /api/accounting/**}, ADMIN +
 * ACCOUNTANT + CA). All calls go through the shared {@link ApiClient}; read
 * endpoints accept either a `financialYearId` or a `from`/`to` date range, and
 * the Day Book additionally an optional `voucherType` filter.
 *
 * <p>CA is read-only server-side (mutating endpoints require ADMIN/ACCOUNTANT);
 * the UI hides post/edit affordances for CA accordingly.
 */
@Injectable({ providedIn: 'root' })
export class LedgerService {
  private readonly api = inject(ApiClient);

  // --- Chart of Accounts: account groups (Req 1) ---------------------------

  /** Lists the account groups, ordered by nature then name. */
  listAccountGroups(): Observable<AccountGroup[]> {
    return this.api.get<AccountGroup[]>('/api/accounting/account-groups');
  }

  /** Creates an account group (ADMIN/ACCOUNTANT). */
  createAccountGroup(request: AccountGroupRequest): Observable<AccountGroup> {
    return this.api.post<AccountGroup>('/api/accounting/account-groups', request);
  }

  // --- Chart of Accounts: ledger accounts (Req 2) --------------------------

  /** Lists the ledger accounts ordered by name, each with its derived nature. */
  listLedgers(): Observable<LedgerAccount[]> {
    return this.api.get<LedgerAccount[]>('/api/accounting/ledgers');
  }

  /** Creates a ledger account under an account group (ADMIN/ACCOUNTANT). */
  createLedger(request: LedgerAccountRequest): Observable<LedgerAccount> {
    return this.api.post<LedgerAccount>('/api/accounting/ledgers', request);
  }

  /** Deletes a ledger account with no posted voucher lines (ADMIN/ACCOUNTANT). */
  deleteLedger(id: number): Observable<void> {
    return this.api.delete<void>(`/api/accounting/ledgers/${id}`);
  }

  // --- Financial years (Req 4) ---------------------------------------------

  /** Lists the financial years, most recent first. */
  listFinancialYears(): Observable<FinancialYear[]> {
    return this.api.get<FinancialYear[]>('/api/accounting/financial-years');
  }

  /** Closes a financial year so vouchers dated within it can no longer be posted (ADMIN/ACCOUNTANT). */
  closeFinancialYear(id: number): Observable<FinancialYear> {
    return this.api.post<FinancialYear>(`/api/accounting/financial-years/${id}/close`);
  }

  // --- Opening balances (Req 3) --------------------------------------------

  /**
   * Lists a financial year's opening balances plus its balancing check.
   * Accepts a `financialYearId` or a `from`/`to` range (defaults to the current FY).
   */
  listOpeningBalances(period?: LedgerPeriod): Observable<OpeningBalances> {
    return this.api.get<OpeningBalances>('/api/accounting/opening-balances', {
      params: this.periodParams(period),
    });
  }

  /** Records (upserts) a ledger account's opening balance for a financial year (ADMIN/ACCOUNTANT). */
  recordOpeningBalance(request: OpeningBalanceRequest): Observable<OpeningBalance> {
    return this.api.post<OpeningBalance>('/api/accounting/opening-balances', request);
  }

  // --- Vouchers: Day Book, detail, post, reverse, audit (Reqs 5, 6, 7, 13, 15) ---

  /**
   * The Day Book — all posted vouchers in the period, chronologically.
   * Accepts a `financialYearId` or a `from`/`to` range and an optional voucher-type filter.
   */
  dayBook(period?: LedgerPeriod, voucherType?: VoucherType | string | null): Observable<DayBook> {
    const params = this.periodParams(period);
    if (voucherType) {
      params['voucherType'] = voucherType;
    }
    return this.api.get<DayBook>('/api/accounting/vouchers', { params });
  }

  /** A single posted voucher with its lines. */
  getVoucher(id: number): Observable<Voucher> {
    return this.api.get<Voucher>(`/api/accounting/vouchers/${id}`);
  }

  /** Posts a balanced, immutable double-entry voucher (ADMIN/ACCOUNTANT). */
  postVoucher(request: PostVoucherRequest): Observable<Voucher> {
    return this.api.post<Voucher>('/api/accounting/vouchers', request);
  }

  /** Reverses a posted voucher with a balancing reversing voucher (ADMIN/ACCOUNTANT). */
  reverseVoucher(id: number): Observable<Voucher> {
    return this.api.post<Voucher>(`/api/accounting/vouchers/${id}/reverse`);
  }

  /** The audit trail for a voucher in chronological order. */
  voucherAudit(id: number): Observable<VoucherAudit[]> {
    return this.api.get<VoucherAudit[]>(`/api/accounting/vouchers/${id}/audit`);
  }

  // --- Read views: Ledger statement, Trial Balance (Reqs 12, 14) -----------

  /**
   * The account statement for a ledger account over a period: opening balance,
   * chronological lines with running balance, and closing balance.
   */
  ledgerStatement(id: number, period?: LedgerPeriod): Observable<LedgerStatement> {
    return this.api.get<LedgerStatement>(`/api/accounting/ledgers/${id}/statement`, {
      params: this.periodParams(period),
    });
  }

  /**
   * The Trial Balance for a period: per-account closing balances with the
   * debit/credit totals and their difference.
   */
  trialBalance(period?: LedgerPeriod): Observable<TrialBalance> {
    return this.api.get<TrialBalance>('/api/accounting/trial-balance', {
      params: this.periodParams(period),
    });
  }

  // --- Financial statements: Balance Sheet, P&L, Cash Flow (Phase 2) -------

  /**
   * The Balance Sheet as at the resolved period's to-date, derived from ledger
   * closing balances. Accepts a `financialYearId` or a `from`/`to` range and an
   * optional comparative prior-period flag (off by default). The full recursive
   * drill-down tree is returned so no separate expand request is needed.
   */
  balanceSheet(period?: LedgerPeriod, comparative = false): Observable<BalanceSheet> {
    return this.api.get<BalanceSheet>('/api/accounting/balance-sheet', {
      params: this.periodParams(period, comparative),
    });
  }

  /**
   * The Profit & Loss statement for the resolved period. Accepts a
   * `financialYearId` or a `from`/`to` range and an optional comparative
   * prior-period flag (off by default), returning the income/expense drill-down
   * trees with the net profit/loss.
   */
  profitAndLoss(period?: LedgerPeriod, comparative = false): Observable<ProfitAndLoss> {
    return this.api.get<ProfitAndLoss>('/api/accounting/profit-and-loss', {
      params: this.periodParams(period, comparative),
    });
  }

  /**
   * The direct-method Cash Flow statement for the resolved period. Accepts a
   * `financialYearId` or a `from`/`to` range and an optional comparative
   * prior-period flag (off by default), returning the opening → inflows →
   * outflows → net → closing figures with the per Cash/Bank ledger drill-down.
   */
  cashFlow(period?: LedgerPeriod, comparative = false): Observable<CashFlow> {
    return this.api.get<CashFlow>('/api/accounting/cash-flow', {
      params: this.periodParams(period, comparative),
    });
  }

  // --- Helpers -------------------------------------------------------------

  /**
   * Builds the `financialYearId` / `from` / `to` query params from a period,
   * plus an optional `comparative=true` flag for the financial-statement
   * endpoints (only emitted when `true`).
   */
  private periodParams(period?: LedgerPeriod, comparative = false): Record<string, string> {
    const p: Record<string, string> = {};
    if (comparative) {
      p['comparative'] = 'true';
    }
    if (!period) {
      return p;
    }
    if (period.financialYearId !== null && period.financialYearId !== undefined) {
      p['financialYearId'] = String(period.financialYearId);
    }
    if (period.from) {
      p['from'] = period.from;
    }
    if (period.to) {
      p['to'] = period.to;
    }
    return p;
  }
}
