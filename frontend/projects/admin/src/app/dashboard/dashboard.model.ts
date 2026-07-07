/**
 * Client-side models for the admin dashboard, mirroring the backend
 * {@code DashboardMetricsResponse}, {@code LiveStats}, and {@code ActivityCards}
 * DTOs and the SSE event stream (Req 19.1-19.7, 11.2, 13.3, 17.4).
 */

/** The dashboard time-period filters (Req 19.2). */
export type MetricsPeriod =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'QUARTERLY'
  | 'YEARLY'
  | 'CUSTOM';

/** The sales-graph bucket granularity (Req 19.4). */
export type SalesBucket = 'DAY' | 'WEEK' | 'MONTH';

/** A selectable period preset with its display label. */
export interface PeriodOption {
  value: MetricsPeriod;
  label: string;
}

/** The metric cards for the selected window (Req 19.1). */
export interface MetricCards {
  totalSales: number;
  totalOrders: number;
  pendingOrders: number;
  packedOrders: number;
  dispatchedOrders: number;
  deliveredOrders: number;
  rtoCount: number;
  courierLostCount: number;
  totalCodPendingFromCourier: number;
  totalLossClaimPendingFromCourier: number;
  conversionRate: number;
}

/** A single sales-graph bucket: label, current sales, previous-period sales (Req 19.4). */
export interface SalesPoint {
  label: string;
  sales: number;
  previousSales: number;
}

/** The sales graph with previous-period comparison and % change (Req 19.4). */
export interface SalesGraph {
  points: SalesPoint[];
  changeApplicable: boolean;
  changePercent: number | null;
}

/** Top performers within the selected window (Req 19.7). */
export interface TopPerformers {
  topSalespersonId: number | null;
  topSalespersonName: string | null;
  topProduct: string | null;
  topState: string | null;
}

/** The full metrics payload of {@code GET /api/admin/metrics} (Req 19.1-19.4, 19.7). */
export interface DashboardMetrics {
  period: string;
  bucket: string;
  from: string | null;
  to: string | null;
  cards: MetricCards;
  salesGraph: SalesGraph;
  topPerformers: TopPerformers;
}

/** Real-time live statistics (Req 19.5). */
export interface LiveStats {
  realtimeOrderCount: number;
  todaysCollection: number;
  totalCodToCollect: number;
  totalLossToClaim: number;
}

/** Activity-card counts (Req 19.6). */
export interface ActivityCards {
  ordersToFulfill: number;
  paymentsToCapture: number;
  rtoAlerts: number;
  whatsappNotificationsSent: number;
  codSettlementsPending: number;
  courierClaimsPending: number;
}

/** The SSE event types the dashboard reacts to (Req 11.2, 13.3, 17.4, 12.4, 14.4). */
export type AdminEventType =
  | 'ORDER_PACKED'
  | 'ORDER_STATUS_CHANGED'
  | 'CLAIM_FILED_REQUIRED'
  | 'COURIER_ASSIGN_FAILED'
  | 'WHATSAPP_FAILED';

/** A real-time notification surfaced in the dashboard feed. */
export interface AdminNotification {
  type: AdminEventType;
  title: string;
  detail: string;
  severity: 'info' | 'success' | 'warning' | 'danger';
  receivedAt: Date;
  orderCode?: string;
}
