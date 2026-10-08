/** The report views the backend can generate (Req 20.1, 20.3; + money/receivables). */
export type ReportType =
  | 'daily'
  | 'monthly'
  | 'product'
  | 'state'
  | 'customer'
  | 'salesperson'
  | 'orders-by-lead-source'
  | 'orders-by-status'
  | 'orders-by-salesperson'
  | 'delivery-outcome'
  | 'payments'
  | 'outstanding'
  | 'cod-remittance'
  | 'expenses'
  | 'purchase-orders'
  | 'returns'
  | 'stock';

/** Export file formats offered for a report (Req 20.4, 23.1). */
export type ExportFormat = 'xlsx' | 'pdf';

/** Vyapar billing export file formats (Req 23.1). */
export type VyaparFormat = 'csv' | 'xlsx';

/**
 * The headline metrics returned with a report (Req 19.3, 19.4, 19.7). Mirrors
 * the backend {@code ReportSummary} DTO.
 */
export interface ReportSummary {
  totalSales: string;
  orderCount: number;
  salesChangeApplicable: boolean;
  salesChangePercent: string | null;
  topSalespersonId: number | null;
  topSalespersonName: string | null;
  topProduct: string | null;
  topState: string | null;
  /** Amount received over the window (excludes cancelled/rejected). */
  totalReceived: string;
  /** Collectible dues still to come in = sum(total − received). */
  totalOutstanding: string;
  /** COD amount pending remittance from the courier. */
  codPendingFromCourier: string;
}

/**
 * A generated report as returned by {@code GET /api/reports/&#123;type&#125;}
 * (Req 20.1-20.3): the applied window, the displayed table (headers + rows,
 * exactly what the exports reproduce), and the summary metrics.
 */
export interface ReportResponse {
  type: string;
  from: string | null;
  to: string | null;
  headers: string[];
  rows: string[][];
  summary: ReportSummary;
}

/** A selectable report type with its display label and category grouping. */
export interface ReportTypeOption {
  value: ReportType;
  label: string;
  /** The optgroup this report belongs to (e.g. Sales, Orders, Money & Receivables). */
  group: string;
}

/** A quick date-range preset (Req 19.2 / 20.2). */
export interface DatePreset {
  key: string;
  label: string;
}
