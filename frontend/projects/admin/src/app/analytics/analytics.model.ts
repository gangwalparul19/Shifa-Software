/** Analytics models (FEATURE-ROADMAP §6), mirroring the backend DTOs. */

// --- §6.1 Sales targets & incentives -------------------------------------

/** A salesperson's target vs achievement for a month. */
export interface SalesTargetRow {
  salespersonId: number;
  salespersonName: string;
  active: boolean;
  month: string;
  targetAmount: string | null;
  achieved: string;
  orderCount: number;
  attainmentPct: number | null;
  incentivePct: string | null;
  incentiveAmount: string;
  targetMet: boolean;
}

/** Upsert payload for a monthly target. */
export interface SetSalesTargetRequest {
  salespersonId: number;
  month: string;
  targetAmount: number;
  incentivePct: number | null;
}

/** Target horizon (CRM expansion Phase 2, Req 12). */
export type TargetPeriodType = 'WEEK' | 'MONTH';

/**
 * Upsert payload for a per-salesperson WEEK or MONTH target with an optional
 * incentive + conversion goal (CRM expansion Req 12). {@code anchor} is any date
 * in the period (ISO yyyy-MM-dd).
 */
export interface SetPeriodTargetRequest {
  salespersonId: number;
  periodType: TargetPeriodType;
  anchor: string;
  targetAmount: number;
  incentivePct?: number | null;
  conversionTarget?: number | null;
}

/** Upsert payload for a TEAM target keyed by the team lead (CRM expansion Req 12). */
export interface SetTeamTargetRequest {
  teamLeadId: number;
  periodType: TargetPeriodType;
  anchor: string;
  targetAmount: number;
  conversionTarget?: number | null;
}

// --- §6.3 Cohort / retention ---------------------------------------------

export interface ReorderBucket {
  label: string;
  count: number;
}

export interface CohortRow {
  cohortMonth: string;
  cohortSize: number;
  retentionPct: number[];
}

export interface RetentionReport {
  totalCustomers: number;
  repeatCustomers: number;
  repeatRatePct: number;
  avgOrdersPerCustomer: number;
  avgDaysToReorder: number;
  reorderBuckets: ReorderBucket[];
  cohorts: CohortRow[];
}

// --- §6.5 Demand & cash forecast -----------------------------------------

export interface ProductForecastRow {
  productId: number;
  productName: string;
  unitsRecent: number;
  avgPerDay: number;
  projectedUnits: number;
}

export interface CashForecast {
  outstandingCod: string;
  windowDays: number;
  collectedInWindow: string;
  avgWeeklyCollection: string;
  estimatedWeeksToClear: number | null;
}

export interface ForecastReport {
  lookbackDays: number;
  horizonDays: number;
  topDemand: ProductForecastRow[];
  cash: CashForecast;
}

// --- §3.3 Delivery-performance analytics (ENHANCEMENT 3.3) ---------------

/** One delivered-vs-failed row (courier / state / pincode band / overall). */
export interface DeliveryPerformanceRow {
  dimension: string;
  delivered: number;
  failed: number;
  total: number;
  successRate: number;
}

/** Delivery-performance report: overall + per-courier / per-state / per-pincode slices. */
export interface DeliveryPerformanceReport {
  overall: DeliveryPerformanceRow;
  byCourier: DeliveryPerformanceRow[];
  byState: DeliveryPerformanceRow[];
  byPincode: DeliveryPerformanceRow[];
}

// --- Growth analytics (overview, customers, products, operations, pointers) ---
//
// Mirrors the backend `GrowthAnalytics` DTOs. Money and counts arrive as JSON numbers
// (rupees, two decimals); percentages are on a 0-100 scale; dates are ISO yyyy-MM-dd.

/** Headline numbers for one period. */
export interface GrowthKpis {
  /** Sales only: rejected, payment-rejected and cancelled orders are "lost orders". */
  orders: number;
  revenue: number;
  avgOrderValue: number;
  customers: number;
  newCustomers: number;
  returningCustomers: number;
  returningRevenue: number;
  returningRevenueSharePct: number;
  discountTotal: number;
  discountedOrdersPct: number;
  lostOrders: number;
  lostOrdersPct: number;
}

/** One bucket of the sales trend (a day, or the Monday of a week for long periods). */
export interface TrendPoint {
  date: string;
  orders: number;
  revenue: number;
  newCustomers: number;
}

/** One slice of a mix table (channel, lead source, payment type). */
export interface MixRow {
  key: string;
  label: string;
  orders: number;
  revenue: number;
  revenueSharePct: number;
  avgOrderValue: number;
  /** Delivered / (delivered + failed) for orders that reached an outcome; null when none yet. */
  successRatePct: number | null;
}

/** One destination (state or city). */
export interface GeoRow {
  name: string;
  orders: number;
  revenue: number;
  revenueSharePct: number;
  customers: number;
  avgOrderValue: number;
}

/** Orders by weekday (Monday first) and hour of day, IST. */
export interface HeatMap {
  /** 7 rows (Monday first) of 24 hourly counts. */
  cells: number[][];
  byWeekday: number[];
  byHour: number[];
  /** 0 = Monday; -1 when there are no orders. */
  peakDay: number;
  /** 0-23; -1 when there are no orders. */
  peakHour: number;
  maxCell: number;
}

export interface OverviewReport {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  current: GrowthKpis;
  previous: GrowthKpis;
  /** DAY, or WEEK for periods longer than about three months. */
  trendBucket: 'DAY' | 'WEEK';
  trend: TrendPoint[];
  channelMix: MixRow[];
  leadSourceMix: MixRow[];
  paymentMix: MixRow[];
  topStates: GeoRow[];
  topCities: GeoRow[];
  heatmap: HeatMap;
}

/** A customer in a list. */
export interface CustomerRow {
  name: string;
  mobile: string;
  orders: number;
  revenue: number;
  lastOrderDate: string;
  daysSinceLastOrder: number;
}

export type SegmentKey =
  | 'CHAMPION'
  | 'LOYAL'
  | 'NEW'
  | 'NEEDS_ATTENTION'
  | 'AT_RISK'
  | 'ONE_TIME_LAPSED'
  | 'HIBERNATING';

/** An engagement segment with a ready-made call list. */
export interface SegmentRow {
  key: SegmentKey;
  label: string;
  description: string;
  /** What the team should do with these customers. */
  action: string;
  customers: number;
  customerSharePct: number;
  revenue: number;
  revenueSharePct: number;
  avgOrders: number;
  avgRecencyDays: number;
  topCustomers: CustomerRow[];
}

/** A count/revenue band (order frequency or recency). */
export interface BandRow {
  label: string;
  customers: number;
  revenue: number;
}

export interface CustomerSegmentsReport {
  asOf: string;
  totalCustomers: number;
  totalRevenue: number;
  avgLifetimeValue: number;
  repeatRatePct: number;
  top10PctRevenueSharePct: number;
  top20PctRevenueSharePct: number;
  /** Repeat customers who have not ordered for 61+ days. */
  atRiskCustomers: number;
  atRiskRevenue: number;
  segments: SegmentRow[];
  orderFrequency: BandRow[];
  recencyBands: BandRow[];
  topCustomers: CustomerRow[];
}

/** One product's performance in the period. */
export interface ProductRow {
  productId: number | null;
  name: string;
  units: number;
  revenue: number;
  orders: number;
  buyers: number;
  repeatBuyerPct: number;
  revenueSharePct: number;
  cumulativeSharePct: number;
  /** A = first 80% of revenue, B = next 15%, C = the rest. */
  abcClass: 'A' | 'B' | 'C';
  previousUnits: number;
  previousRevenue: number;
  /** Null when the product had no sales in the previous period. */
  revenueGrowthPct: number | null;
}

/** Two products bought in the same order. */
export interface AffinityPair {
  productA: string;
  productB: string;
  orders: number;
  supportPct: number;
  lift: number;
}

export interface ProductPerformanceReport {
  from: string;
  to: string;
  totalUnits: number;
  totalRevenue: number;
  productsSold: number;
  top3RevenueSharePct: number;
  multiProductOrders: number;
  multiProductOrdersPct: number;
  avgUnitsPerOrder: number;
  products: ProductRow[];
  pairs: AffinityPair[];
}

/** A fulfilment time statistic in hours; the figures are null when there are no samples. */
export interface SpeedStat {
  key: 'APPROVAL' | 'DISPATCH' | 'DELIVERY' | 'END_TO_END';
  label: string;
  samples: number;
  medianHours: number | null;
  p90Hours: number | null;
  avgHours: number | null;
}

export interface OutcomeRow {
  key: string;
  label: string;
  orders: number;
  sharePct: number;
}

export interface PaymentRiskRow {
  key: 'FULLY_PAID' | 'PARTIALLY_PAID' | 'COD';
  label: string;
  orders: number;
  delivered: number;
  failed: number;
  successRatePct: number;
  revenue: number;
}

export interface ReasonRow {
  key: string;
  label: string;
  orders: number;
}

export interface OperationsReport {
  from: string;
  to: string;
  /** Every order placed in the period, any status. */
  orders: number;
  speed: SpeedStat[];
  outcomes: OutcomeRow[];
  paymentRisk: PaymentRiskRow[];
  rejectReasons: ReasonRow[];
  rtoReasons: ReasonRow[];
}

export type PointerSeverity = 'WARNING' | 'OPPORTUNITY' | 'POSITIVE' | 'INFO';

/** An actionable finding. */
export interface Pointer {
  id: string;
  severity: PointerSeverity;
  area: string;
  title: string;
  detail: string;
  /** The headline figure behind it, ready to show. */
  metric: string;
  actionLabel: string | null;
  /** An app route, possibly with `?tab=` for this page; null when there is nothing to open. */
  actionRoute: string | null;
}

export interface PointersReport {
  from: string;
  to: string;
  pointers: Pointer[];
}

/** A column of a downloadable dataset. */
export interface DatasetColumn {
  name: string;
  type: string;
  description: string;
}

/** A downloadable dataset, documented for people who load it into other tools. */
export interface DatasetInfo {
  key: string;
  label: string;
  description: string;
  grain: string;
  usesDateRange: boolean;
  columns: DatasetColumn[];
}

// --- Lead funnel (existing /api/leads/reports/* endpoints) -----------------

export interface LeadConversionRow {
  key: string;
  leads: number;
  won: number;
  /** Percentage, 0-100. */
  conversionRate: number;
}

export interface LeadConversionReport {
  bySource: LeadConversionRow[];
  byOwner: LeadConversionRow[];
}

export interface LeadLostReasonReport {
  rows: { reason: string; count: number }[];
  total: number;
}

export interface LeadPipelineReport {
  rows: { status: string; count: number }[];
  total: number;
}

/** The lead funnel for a period. */
export interface LeadFunnel {
  conversion: LeadConversionReport;
  lostReasons: LeadLostReasonReport;
  pipeline: LeadPipelineReport;
}
