/**
 * Types for the channel-aware admin dashboard
 * ({@code GET /api/admin/dashboard/channel}). Mirrors the backend
 * {@code ChannelDashboardResponse}. Money arrives as JSON numbers.
 */

/** Which orders the dashboard is scoped to. */
export type DashboardChannel = 'ALL' | 'PORTAL' | 'SHOPIFY';

export interface ChannelTotals {
  orders: number;
  revenue: number;
  avgOrderValue: number;
  revenueSharePct: number;
  previousOrders: number;
  previousRevenue: number;
  /** null when there is no previous-period revenue to compare against. */
  revenueChangePct: number | null;
  ordersChangePct: number | null;
}

export interface ChannelSplit {
  all: ChannelTotals;
  portal: ChannelTotals;
  shopify: ChannelTotals;
}

export interface ChannelKpis {
  orders: number;
  revenue: number;
  avgOrderValue: number;
  delivered: number;
  inProgress: number;
  failedReturned: number;
  cancelledRejected: number;
  /** delivered ÷ (delivered + returned/failed); null before any order reaches an outcome. */
  deliverySuccessPct: number | null;
  codToCollect: number;
  previousOrders: number;
  previousRevenue: number;
  revenueChangePct: number | null;
  ordersChangePct: number | null;
}

export interface ChannelTrendPoint {
  label: string;
  portal: number;
  shopify: number;
  total: number;
  previousTotal: number;
}

export interface ChannelStageCount {
  /** Backend OrderStatusGroup key — also the Orders page ?statusGroup= value. */
  group: string;
  label: string;
  count: number;
}

export interface PortalQueues {
  pendingApproval: number;
  toPack: number;
  awaitingHandover: number;
  awaitingDispatch: number;
}

export interface ShopifyQueues {
  stuck: number;
  labelsToPrint: number;
  awaitingPickup: number;
}

export interface ChannelQueues {
  portal: PortalQueues | null;
  shopify: ShopifyQueues | null;
}

export interface PaymentMixRow {
  status: string;
  label: string;
  orders: number;
  amount: number;
}

export interface ChannelPayments {
  mix: PaymentMixRow[];
  collectedByTeam: number;
  paidOnShopify: number;
  codToCollect: number;
  codPendingFromCourier: number;
  lossClaimPending: number;
  todayOrders: number;
  todayCollectedByTeam: number;
  todayPaidOnShopify: number;
}

export interface RankRow {
  name: string;
  count: number;
  revenue: number;
}

export interface ChannelPerformance {
  topSalespeople: RankRow[];
  topProducts: RankRow[];
  topStates: RankRow[];
}

export interface ChannelDashboardData {
  channel: DashboardChannel;
  period: string;
  bucket: string;
  from: string | null;
  to: string | null;
  split: ChannelSplit;
  kpis: ChannelKpis;
  trend: ChannelTrendPoint[];
  pipeline: ChannelStageCount[];
  queues: ChannelQueues;
  payments: ChannelPayments;
  performance: ChannelPerformance;
}
