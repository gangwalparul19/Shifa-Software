import { Money } from 'core';

/**
 * Client-side models for the Statistical Insights feature, mirroring the backend
 * {@code com.shifa.oms.insights} DTOs (design §Frontend, §API). The read DTO
 * flattens the engine enums to their names, so the payload is stable and
 * frontend-friendly.
 */

/** The insight severity (backend {@code InsightSeverity}). */
export type Severity = 'INFO' | 'WARNING' | 'DANGER';

/** The insight family (backend {@code InsightType}). */
export type InsightType =
  | 'SALES_ANOMALY'
  | 'LOW_STOCK_REORDER'
  | 'RTO_RISK'
  | 'COURIER_SCORECARD'
  | 'RETURN_RATE_ANOMALY'
  | 'COD_OUTSTANDING_BUILDUP'
  | 'LEAD_SOURCE_CONVERSION';

/** The scope an insight is attached to (backend {@code InsightScope}). */
export type InsightScope = 'GLOBAL' | 'PRODUCT' | 'COURIER' | 'SALESPERSON' | 'ORDER';

/**
 * A persisted insight served by {@code GET /api/insights} (mirrors the backend
 * {@code InsightResponse}). The natural key is
 * {@code (type, scope, scopeRefId, computedDate)}; GLOBAL-scope rows carry the
 * sentinel {@code scopeRefId = 0}.
 */
export interface Insight {
  id: number;
  type: InsightType | string;
  scope: InsightScope | string;
  scopeRefId?: number | null;
  scopeLabel?: string | null;
  severity: Severity;
  title: string;
  detail?: string | null;
  metricValue?: Money | number | null;
  computedDate: string;
  dismissed: boolean;
}

/** Filters for the scoped insight list (`GET /api/insights`). */
export interface InsightListQuery {
  type?: InsightType | string | null;
  scope?: InsightScope | string | null;
  severity?: Severity | null;
  includeDismissed?: boolean | null;
}

/**
 * The response of {@code POST /api/insights/recompute} (mirrors the backend
 * {@code RecomputeResponse}): how many insights were persisted and the date
 * they were computed for.
 */
export interface RecomputeResponse {
  computed: number;
  computedDate: string;
}

/** The severity display order for grouping: DANGER first, then WARNING, then INFO. */
export const SEVERITY_ORDER: Severity[] = ['DANGER', 'WARNING', 'INFO'];

/** Human labels for each {@link Severity}. */
export const SEVERITY_LABELS: Record<Severity, string> = {
  INFO: 'Info',
  WARNING: 'Warning',
  DANGER: 'Danger',
};

/** Human labels for each {@link InsightType} (design §Frontend). */
export const INSIGHT_TYPE_LABELS: Record<string, string> = {
  SALES_ANOMALY: 'Sales anomaly',
  LOW_STOCK_REORDER: 'Reorder suggestion',
  RTO_RISK: 'RTO risk',
  COURIER_SCORECARD: 'Courier scorecard',
  RETURN_RATE_ANOMALY: 'Return-rate anomaly',
  COD_OUTSTANDING_BUILDUP: 'COD outstanding',
  LEAD_SOURCE_CONVERSION: 'Lead-source conversion',
};

/**
 * Maps a {@link Severity} to its Tabler badge classes (matching how
 * {@code leads.model.ts} maps status pills): INFO → blue, WARNING → yellow,
 * DANGER → red. Kept in sync with the coarse colour-group helper below.
 */
export function severityPillClass(severity: Severity): string {
  switch (severity) {
    case 'DANGER':
      return 'bg-red-lt';
    case 'WARNING':
      return 'bg-yellow-lt';
    default:
      return 'bg-blue-lt';
  }
}

/** The coarse colour group for a severity pill / card accent. */
export function severityGroupClass(severity: Severity): 'is-red' | 'is-amber' | 'is-blue' {
  switch (severity) {
    case 'DANGER':
      return 'is-red';
    case 'WARNING':
      return 'is-amber';
    default:
      return 'is-blue';
  }
}

/** Human label for an {@link InsightType} (falls back to the raw value). */
export function insightTypeLabel(type: InsightType | string): string {
  return INSIGHT_TYPE_LABELS[type] ?? type;
}

/** Formats an insight metric value (may be null); decimal strings pass through. */
export function insightMetric(value: Money | number | undefined | null): string | null {
  if (value === undefined || value === null || value === '') {
    return null;
  }
  return `${value}`;
}
