/**
 * Frontend models mirroring the backend GST returns & filing DTOs (Phase 3 —
 * `com.shifa.oms.gst.filing.dto`). Field names match the Java records one-to-one so the JSON binds
 * directly. BigDecimal figures serialise as numbers (typed `number | string` to match the existing
 * CA GST report models), enums serialise as their `name()` (string unions), `LocalDate` as an ISO
 * `yyyy-MM-dd` string and `LocalDateTime` as an ISO date-time string.
 *
 * Restricted to the ADMIN + CA roles (`adminOrCaGuard`); consumed by `FilingService` and the filing
 * calendar / reconciliation components.
 */

/* ── Enums (string unions mirroring gst.filing.domain) ─────────────────────── */

/** The filing lifecycle state of one return type for one period. */
export type FilingStatus = 'NOT_STARTED' | 'PREPARED' | 'FILED';

/** The two monthly return types (GSTR-1 due the 11th, GSTR-3B the 20th of the following month). */
export type ReturnType = 'GSTR1' | 'GSTR3B';

/** A GSTR-1 amendment section a post-filing correction is routed into. */
export type AmendmentTable = 'B2BA' | 'B2CSA' | 'CDNRA';

/** The routing lifecycle status of a post-filing correction. */
export type AmendmentStatus = 'PENDING' | 'ROUTED' | 'MANUAL_REVIEW';

/**
 * The sign convention for a reconciliation difference (`returnValue − ledgerValue`):
 * `RETURN_OVER_LEDGER` (positive), `LEDGER_OVER_RETURN` (negative), `EQUAL` (zero).
 */
export type DifferenceDirection = 'RETURN_OVER_LEDGER' | 'LEDGER_OVER_RETURN' | 'EQUAL';

/** The five stable reconciliation compared-figure keys (drill-down routing). */
export type ReconciliationFigureKey =
  | 'GSTR1_OUTPUT_TAX'
  | 'GSTR3B_OUTPUT_TAX'
  | 'GSTR3B_ITC'
  | 'NET_GST_PAYABLE'
  | 'TAXABLE_OUTWARD_TURNOVER';

/* ── Filing status & lifecycle ─────────────────────────────────────────────── */

/** The current filing status of both return types for a selected period (Reqs 1.2, 1.6). */
export interface FilingStatusResponse {
  month: number;
  year: number;
  gstr1: FilingStatus;
  gstr3b: FilingStatus;
}

/** Request to mark a return prepared: NOT_STARTED → PREPARED (Reqs 1.3, 1.7). */
export interface PrepareReturnRequest {
  month: number;
  year: number;
  returnType: ReturnType;
}

/** Request to file a prepared return: PREPARED → FILED, with an optional ack reference (Req 1.5). */
export interface FileReturnRequest {
  month: number;
  year: number;
  returnType: ReturnType;
  /** Optional portal acknowledgement reference (≤ 50 chars). */
  ackReference?: string | null;
}

/** Request to reopen a filed return: FILED → PREPARED, ADMIN/CA only (Reqs 2.4, 2.6, 2.7). */
export interface ReopenReturnRequest {
  month: number;
  year: number;
  returnType: ReturnType;
}

/* ── Filing calendar (Reqs 4.1–4.6) ────────────────────────────────────────── */

/** One row of the filing calendar: a return type for a month, its due date, status and flags. */
export interface CalendarEntry {
  returnType: ReturnType;
  month: number;
  year: number;
  /** The statutory portal due date as `yyyy-MM-dd`. */
  dueDate: string;
  status: FilingStatus;
  reminder: boolean;
  overdue: boolean;
  overdueDays: number;
  dueToday: boolean;
}

/** The filing calendar for an Indian financial year — one entry per month × return type. */
export interface CalendarResponse {
  financialYearStartYear: number;
  entries: CalendarEntry[];
}

/* ── Filing snapshot history (Req 5.7) ─────────────────────────────────────── */

/** One entry in a return's filing-snapshot history (metadata only, never the payload figures). */
export interface Snapshot {
  returnType: ReturnType;
  month: number;
  year: number;
  /** The monotonic snapshot version (from 1; highest is current — Req 5.6). */
  version: number;
  filedBy: string;
  /** The filing timestamp (Asia/Kolkata, to the second) as an ISO date-time string. */
  filedAt: string;
}

/** Alias matching the backend DTO name; identical shape to {@link Snapshot}. */
export type SnapshotResponse = Snapshot;

/* ── Reconciliation (Reqs 7, 8, 9) ─────────────────────────────────────────── */

/** One compared figure: the return value against its ledger/statement value + the signed diff. */
export interface ReconciliationFigure {
  key: ReconciliationFigureKey | string;
  label: string;
  returnValue: number | string;
  ledgerValue: number | string;
  difference: number | string;
  direction: DifferenceDirection;
  reconciled: boolean;
  /** When `false`, the ledger value and difference are not meaningful (source unavailable — Req 8.5). */
  comparisonAvailable: boolean;
}

/** The reconciliation summary for a period: the five compared figures + the period verdict. */
export interface ReconciliationSummary {
  month: number;
  year: number;
  /** `true` iff every compared figure is within tolerance (Req 9.6). */
  periodReconciled: boolean;
  figures: ReconciliationFigure[];
}

/** One contributing row behind a reconciliation figure (drill-down, Req 9.3). */
export interface DrillDownRow {
  /** The contributing document identifier (order code, `VL-<id>`). */
  identifier: string;
  /** The contributor type (`ORDER` or `VOUCHER_LINE`). */
  type: string;
  signedContribution: number | string;
}

/* ── Amendments (Req 3) ────────────────────────────────────────────────────── */

/** A post-filing correction routed the GST way (mirrors `ReturnAmendment`). */
export interface Amendment {
  id: number;
  /** The routed amendment section, or `null` while PENDING / MANUAL_REVIEW. */
  amendmentTable: AmendmentTable | null;
  status: AmendmentStatus;
  originalPeriodMonth: number;
  originalPeriodYear: number;
  originalDocumentRef: string;
  /** The target period the amendment lands in, or `null` while PENDING. */
  targetPeriodMonth: number | null;
  targetPeriodYear: number | null;
  originalValueJson: string;
  correctedValueJson: string;
  createdAt: string;
  updatedAt: string;
}

/** Alias matching the backend DTO name; identical shape to {@link Amendment}. */
export type AmendmentResponse = Amendment;

/** Request to resolve a manual-review amendment by routing it into a table + open period (Req 3.7). */
export interface AmendmentReviewRequest {
  amendmentTable: AmendmentTable;
  targetMonth: number;
  targetYear: number;
  note?: string | null;
}

/* ── Pill / label helpers (Tabler badge classes, mirroring the feature model style) ── */

/** A Tabler badge class for a filing status pill (grey NOT_STARTED / yellow PREPARED / green FILED). */
export function filingStatusPillClass(status: FilingStatus): string {
  switch (status) {
    case 'FILED':
      return 'badge bg-green-lt';
    case 'PREPARED':
      return 'badge bg-yellow-lt';
    case 'NOT_STARTED':
    default:
      return 'badge bg-secondary-lt';
  }
}

/** A human-readable label for a filing status. */
export function filingStatusLabel(status: FilingStatus): string {
  switch (status) {
    case 'FILED':
      return 'Filed';
    case 'PREPARED':
      return 'Prepared';
    case 'NOT_STARTED':
    default:
      return 'Not started';
  }
}

/** The short label for a return type (`GSTR-1` / `GSTR-3B`). */
export function returnTypeLabel(type: ReturnType): string {
  return type === 'GSTR1' ? 'GSTR-1' : 'GSTR-3B';
}

/** The Tabler badge class for a due-date reminder badge (blue). */
export function reminderBadgeClass(): string {
  return 'badge bg-blue-lt';
}

/** The Tabler badge class for an overdue badge (red). */
export function overdueBadgeClass(): string {
  return 'badge bg-red-lt';
}

/** A Tabler badge class for an amendment routing status pill. */
export function amendmentStatusPillClass(status: AmendmentStatus): string {
  switch (status) {
    case 'ROUTED':
      return 'badge bg-green-lt';
    case 'MANUAL_REVIEW':
      return 'badge bg-red-lt';
    case 'PENDING':
    default:
      return 'badge bg-yellow-lt';
  }
}

/** A human-readable label for a reconciliation difference direction. */
export function directionLabel(direction: DifferenceDirection): string {
  switch (direction) {
    case 'RETURN_OVER_LEDGER':
      return 'Return over ledger';
    case 'LEDGER_OVER_RETURN':
      return 'Ledger over return';
    case 'EQUAL':
    default:
      return 'Equal';
  }
}

/** A Tabler badge class for a reconciled/unreconciled indicator (green when reconciled, red otherwise). */
export function reconciledIndicatorClass(reconciled: boolean): string {
  return reconciled ? 'badge bg-green-lt' : 'badge bg-red-lt';
}
