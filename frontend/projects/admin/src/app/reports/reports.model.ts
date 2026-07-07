/** The report views the backend can generate (Req 20.1, 20.3). */
export type ReportType = 'daily' | 'monthly' | 'product' | 'state' | 'salesperson';

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
  topProduct: string | null;
  topState: string | null;
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

/** A selectable report type with its display label. */
export interface ReportTypeOption {
  value: ReportType;
  label: string;
}

/** A quick date-range preset (Req 19.2 / 20.2). */
export interface DatePreset {
  key: string;
  label: string;
}
