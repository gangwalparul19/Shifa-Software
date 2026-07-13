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
