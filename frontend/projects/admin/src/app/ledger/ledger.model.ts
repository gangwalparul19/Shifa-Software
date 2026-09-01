/**
 * Frontend models mirroring the backend General Ledger DTOs
 * (`com.shifa.oms.ledger.dto`, served under `/api/accounting/**`).
 *
 * Money values are `BigDecimal` on the wire and — consistent with the other
 * finance feature (`ca-gst/gst.model.ts`) — are typed `number | string` here
 * (Jackson serialises `BigDecimal` as a JSON number, but keeping the string
 * option makes the models tolerant of either rendering). Enums serialise as
 * their `name()`, `LocalDate` as `yyyy-MM-dd`, and `LocalDateTime` as an ISO
 * string. Field names match the Java records exactly so the JSON binds directly.
 */

/** A money amount as it arrives on the wire (number, or a decimal string). */
export type Money = number | string;

/* ── Enums (mirror the pure `ledger.domain` enums) ───────────────────────── */

/** The five accounting natures — mirrors `ledger.domain.AccountNature`. */
export type AccountNature = 'ASSET' | 'LIABILITY' | 'INCOME' | 'EXPENSE' | 'EQUITY';

/** All five natures in display order (for pickers / iteration). */
export const ACCOUNT_NATURES: readonly AccountNature[] = [
  'ASSET',
  'LIABILITY',
  'INCOME',
  'EXPENSE',
  'EQUITY',
];

/** The eight supported voucher types — mirrors `ledger.domain.VoucherType`. */
export type VoucherType =
  | 'JOURNAL'
  | 'PAYMENT'
  | 'RECEIPT'
  | 'CONTRA'
  | 'SALES'
  | 'PURCHASE'
  | 'DEBIT_NOTE'
  | 'CREDIT_NOTE';

/** All eight voucher types in display order (for pickers / iteration). */
export const VOUCHER_TYPES: readonly VoucherType[] = [
  'JOURNAL',
  'PAYMENT',
  'RECEIPT',
  'CONTRA',
  'SALES',
  'PURCHASE',
  'DEBIT_NOTE',
  'CREDIT_NOTE',
];

/** The debit or credit side of a posting — mirrors `ledger.domain.DrCr`. */
export type DrCr = 'DEBIT' | 'CREDIT';

/* ── Chart of Accounts (Reqs 1, 2) ───────────────────────────────────────── */

/** An account group in the chart-of-accounts hierarchy (`AccountGroupResponse`). */
export interface AccountGroup {
  id: number;
  name: string;
  nature: AccountNature;
  parentGroupId: number | null;
  systemGenerated: boolean;
}

/** A postable ledger account; its `nature` is derived from its group (`LedgerAccountResponse`). */
export interface LedgerAccount {
  id: number;
  name: string;
  accountGroupId: number;
  nature: AccountNature;
  /** Control-account natural key (e.g. `SALES`) for a seeded control ledger, else null. */
  controlKey: string | null;
  systemGenerated: boolean;
}

/* ── Financial years (Req 4) ─────────────────────────────────────────────── */

/** An Indian financial year (1 Apr – 31 Mar) (`FinancialYearResponse`). */
export interface FinancialYear {
  id: number;
  startDate: string;
  endDate: string;
  label: string;
  closed: boolean;
  closedAt: string | null;
  closedBy: string | null;
}

/* ── Opening balances (Req 3) ────────────────────────────────────────────── */

/** A recorded opening balance for a (ledger account, financial year) pair (`OpeningBalanceResponse`). */
export interface OpeningBalance {
  id: number;
  ledgerAccountId: number;
  financialYearId: number;
  amount: Money;
  side: DrCr;
}

/** A financial year's opening-balance balancing check (`OpeningBalanceResponse.Check`). */
export interface OpeningBalanceCheck {
  balanced: boolean;
  debitTotal: Money;
  creditTotal: Money;
  difference: Money;
}

/** A financial year's opening balances plus its balancing check (`LedgerController.OpeningBalancesResponse`). */
export interface OpeningBalances {
  financialYearId: number;
  balances: OpeningBalance[];
  check: OpeningBalanceCheck;
}

/* ── Vouchers (Reqs 5, 6, 7) ─────────────────────────────────────────────── */

/** One Dr/Cr line of a posted voucher (`VoucherResponse.Line`). */
export interface VoucherLine {
  ledgerAccountId: number;
  /** Debit amount, or null when this is a credit line. */
  debit: Money | null;
  /** Credit amount, or null when this is a debit line. */
  credit: Money | null;
  lineOrder: number;
  narration: string | null;
}

/** A posted, immutable double-entry voucher and its lines (`VoucherResponse`). */
export interface Voucher {
  id: number;
  type: VoucherType;
  date: string;
  financialYearId: number;
  reference: string;
  narration: string;
  postedAt: string;
  postedBy: string | null;
  /** Source-document type for an auto-posted voucher, else null. */
  sourceType: string | null;
  /** Source-document id for an auto-posted voucher, else null. */
  sourceId: number | null;
  /** Original voucher id when this is a reversing voucher, else null. */
  reversesVoucherId: number | null;
  /** Reversing voucher id when this voucher has been reversed, else null. */
  reversedByVoucherId: number | null;
  lines: VoucherLine[];
}

/* ── Day Book (Req 13) ───────────────────────────────────────────────────── */

/** One line of a Day Book voucher row (`DayBookResponse.Line`). */
export interface DayBookLine {
  ledgerAccountId: number;
  debit: Money | null;
  credit: Money | null;
}

/** A Day Book row for one voucher (`DayBookResponse.Row`). */
export interface DayBookRow {
  voucherId: number;
  reference: string;
  date: string;
  type: VoucherType;
  narration: string;
  lines: DayBookLine[];
}

/** The Day Book — chronological vouchers in a period (`DayBookResponse`). */
export interface DayBook {
  from: string;
  to: string;
  financialYearId: number | null;
  voucherType: VoucherType | null;
  rows: DayBookRow[];
}

/* ── Ledger statement (Req 12) ───────────────────────────────────────────── */

/** A balance expressed as a reporting side and non-negative magnitude (`Balance`). */
export interface LedgerBalance {
  side: DrCr;
  magnitude: Money;
}

/** One ledger-statement row (`LedgerStatementResponse.Row`). */
export interface LedgerStatementRow {
  voucherId: number | null;
  reference: string | null;
  date: string | null;
  type: VoucherType | null;
  narration: string | null;
  debit: Money | null;
  credit: Money | null;
  balanceAfter: LedgerBalance | null;
}

/** An account statement over a period (`LedgerStatementResponse`). */
export interface LedgerStatement {
  ledgerAccountId: number;
  ledgerName: string;
  nature: AccountNature;
  from: string;
  to: string;
  financialYearId: number | null;
  opening: LedgerBalance | null;
  rows: LedgerStatementRow[];
  closing: LedgerBalance | null;
}

/* ── Trial balance (Req 14) ──────────────────────────────────────────────── */

/** One Trial Balance line — a ledger account's closing balance (`TrialBalanceResponse.Row`). */
export interface TrialBalanceRow {
  ledgerId: number;
  ledgerName: string;
  nature: AccountNature;
  side: DrCr;
  magnitude: Money;
  debitBalance: Money;
  creditBalance: Money;
}

/** The Trial Balance for a period, with totals and the balancing check (`TrialBalanceResponse`). */
export interface TrialBalance {
  from: string;
  to: string;
  financialYearId: number | null;
  rows: TrialBalanceRow[];
  debitTotal: Money;
  creditTotal: Money;
  difference: Money;
  balanced: boolean;
}

/* ── Financial statements (Phase 2 — Balance Sheet / P&L / Cash Flow) ────── */

/**
 * A recursive grouped-node in a statement drill-down tree, mirroring the backend
 * `ledger.statements.dto.StatementNodeResponse`. Each node reports its group
 * subtotal as a reporting {@link DrCr} `side` plus a non-negative `amount`
 * magnitude, and carries its nested `childGroups` and leaf `ledgers` so
 * drill-down needs no separate request. When a comparative prior period was
 * requested, `priorAmount` carries the prior-period subtotal of the same group
 * (aligned by `groupId`), else null.
 */
export interface StatementNode {
  groupId: number;
  name: string;
  nature: AccountNature;
  side: DrCr;
  amount: Money;
  childGroups: StatementNode[];
  ledgers: LedgerLine[];
  priorAmount: Money | null;
}

/**
 * A leaf ledger balance within a statement group node — the bottom of a
 * Tally-style drill-down (`ledger.statements.dto.LedgerLineResponse`). The
 * balance is a reporting {@link DrCr} `side` plus a non-negative `amount`;
 * `priorAmount` carries the prior-period magnitude when comparative, else null.
 */
export interface LedgerLine {
  ledgerId: number;
  name: string;
  side: DrCr;
  amount: Money;
  priorAmount: Money | null;
}

/**
 * The synthetic retained-earnings (current-period net-profit) line injected into
 * the Balance Sheet's equity side (`ledger.statements.dto.RetainedEarningsLine`).
 * `side` is CREDIT for a profit, DEBIT for a loss; `priorAmount` is the prior
 * period's magnitude when comparative, else null.
 */
export interface RetainedEarningsLine {
  label: string;
  side: DrCr;
  amount: Money;
  priorAmount: Money | null;
}

/**
 * The Balance Sheet as at a date, derived from ledger closing balances
 * (`ledger.statements.dto.BalanceSheetResponse`). ASSET-nature groups form the
 * `assets` side; LIABILITY + EQUITY groups plus the injected `retainedEarnings`
 * line form the liabilities-and-equity side. The side totals carry the domain's
 * signed totals so `difference == assetsTotal − liabilitiesAndEquityTotal`.
 */
export interface BalanceSheet {
  asAtDate: string;
  from: string;
  to: string;
  financialYearId: number | null;
  comparative: boolean;
  assets: StatementNode[];
  assetsTotal: Money;
  liabilitiesAndEquity: StatementNode[];
  retainedEarnings: RetainedEarningsLine;
  liabilitiesAndEquityTotal: Money;
  difference: Money;
  balanced: boolean;
  priorAssetsTotal: Money | null;
  priorLiabilitiesAndEquityTotal: Money | null;
}

/**
 * The Profit & Loss statement for a period
 * (`ledger.statements.dto.ProfitAndLossResponse`). INCOME-nature groups form the
 * `income` side and EXPENSE-nature groups the `expenses` side; totals are
 * positive magnitudes. `grossProfit` is present only where the Chart of Accounts
 * distinguishes Direct groups, else null. `netProfit` is `totalIncome −
 * totalExpenses` (negative denotes a loss); `netLoss`/`netLossAmount` present the
 * loss for convenience.
 */
export interface ProfitAndLoss {
  from: string;
  to: string;
  financialYearId: number | null;
  comparative: boolean;
  income: StatementNode[];
  totalIncome: Money;
  expenses: StatementNode[];
  totalExpenses: Money;
  grossProfit: Money | null;
  netProfit: Money;
  netLoss: boolean;
  netLossAmount: Money;
  priorTotalIncome: Money | null;
  priorTotalExpenses: Money | null;
  priorNetProfit: Money | null;
}

/**
 * A single Cash/Bank ledger's direct-method split within the Cash Flow drill-down
 * (`ledger.statements.dto.CashBankLedgerLine`). Cash/Bank ledgers are ASSET
 * nature, so `opening`/`closing` are signed (debit-positive) balances and
 * `inflows`/`outflows` are the in-period debit/credit movement magnitudes.
 */
export interface CashBankLedgerLine {
  ledgerId: number;
  name: string;
  opening: Money;
  inflows: Money;
  outflows: Money;
  closing: Money;
}

/**
 * The direct-method Cash Flow statement for a period
 * (`ledger.statements.dto.CashFlowResponse`): the `opening → inflows → outflows →
 * net → closing` figures (opening/closing as a {@link DrCr} side + magnitude) with
 * the per-Cash/Bank-ledger drill-down and, when comparative, the prior opening /
 * net / closing figures.
 */
export interface CashFlow {
  from: string;
  to: string;
  financialYearId: number | null;
  comparative: boolean;
  openingSide: DrCr;
  openingBalance: Money;
  inflows: Money;
  outflows: Money;
  netCashMovement: Money;
  closingSide: DrCr;
  closingBalance: Money;
  ledgers: CashBankLedgerLine[];
  priorOpeningBalance: Money | null;
  priorNetCashMovement: Money | null;
  priorClosingBalance: Money | null;
}

/* ── Voucher audit trail (Req 15) ────────────────────────────────────────── */

/** One voucher audit-trail event (`VoucherAuditResponse`). */
export interface VoucherAudit {
  action: string;
  actorUsername: string | null;
  actorUserId: number | null;
  entityId: string;
  summary: string;
  createdAt: string;
}

/* ── Request payloads (mirror the backend request records) ───────────────── */

/** Create payload for an account group (`AccountGroupRequest`). */
export interface AccountGroupRequest {
  name: string;
  /** Required only for a root group; ignored when `parentGroupId` is supplied. */
  nature?: AccountNature | null;
  parentGroupId?: number | null;
}

/** Create payload for a postable ledger account (`LedgerAccountRequest`). */
export interface LedgerAccountRequest {
  name: string;
  accountGroupId: number;
}

/** Create/upsert payload for a ledger-account opening balance (`OpeningBalanceRequest`). */
export interface OpeningBalanceRequest {
  ledgerAccountId: number;
  financialYearId: number;
  amount: Money;
  side: DrCr;
}

/** One Dr/Cr line of a voucher-post payload (`PostVoucherRequest.Line`). */
export interface PostVoucherLine {
  ledgerAccountId: number;
  side: DrCr;
  amount: Money;
  narration?: string | null;
}

/** Post payload for a manual double-entry voucher (`PostVoucherRequest`). */
export interface PostVoucherRequest {
  type: VoucherType | string;
  date: string;
  narration: string;
  lines: PostVoucherLine[];
}

/* ── Pill / label helpers ────────────────────────────────────────────────── */

/** Human label for an {@link AccountNature} (title-case). */
const ACCOUNT_NATURE_LABELS: Record<AccountNature, string> = {
  ASSET: 'Asset',
  LIABILITY: 'Liability',
  INCOME: 'Income',
  EXPENSE: 'Expense',
  EQUITY: 'Equity',
};

/** Human label for a {@link VoucherType} (falls back to the raw value). */
const VOUCHER_TYPE_LABELS: Record<VoucherType, string> = {
  JOURNAL: 'Journal',
  PAYMENT: 'Payment',
  RECEIPT: 'Receipt',
  CONTRA: 'Contra',
  SALES: 'Sales',
  PURCHASE: 'Purchase',
  DEBIT_NOTE: 'Debit Note',
  CREDIT_NOTE: 'Credit Note',
};

/**
 * Maps an {@link AccountNature} to its Tabler badge classes (matching how
 * `insights.model.ts` maps severity pills): ASSET → blue, LIABILITY → yellow,
 * INCOME → green, EXPENSE → red, EQUITY → purple.
 */
export function naturePillClass(nature: AccountNature): string {
  switch (nature) {
    case 'ASSET':
      return 'bg-blue-lt';
    case 'LIABILITY':
      return 'bg-yellow-lt';
    case 'INCOME':
      return 'bg-green-lt';
    case 'EXPENSE':
      return 'bg-red-lt';
    case 'EQUITY':
      return 'bg-purple-lt';
    default:
      return 'bg-secondary-lt';
  }
}

/** Human label for an {@link AccountNature} (falls back to the raw value). */
export function natureLabel(nature: AccountNature | string): string {
  return ACCOUNT_NATURE_LABELS[nature as AccountNature] ?? nature;
}

/** Human label for a {@link VoucherType} (falls back to the raw value). */
export function voucherTypeLabel(type: VoucherType | string): string {
  return VOUCHER_TYPE_LABELS[type as VoucherType] ?? type;
}

/** Short accountant's label for a {@link DrCr} side — `Dr` / `Cr`. */
export function drcrLabel(side: DrCr): string {
  return side === 'DEBIT' ? 'Dr' : 'Cr';
}

/** Maps a {@link DrCr} side to Tabler badge classes: DEBIT → blue, CREDIT → green. */
export function drcrPillClass(side: DrCr): string {
  return side === 'DEBIT' ? 'bg-blue-lt' : 'bg-green-lt';
}
