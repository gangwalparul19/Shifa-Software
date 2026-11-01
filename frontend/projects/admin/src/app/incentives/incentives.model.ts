/**
 * Models for the daily sales tracker and the daily incentive rules, mirroring the backend DTOs in
 * {@code com.shifa.oms.incentive.dto}. Money arrives as JSON numbers; dates as yyyy-MM-dd strings in
 * the Asia/Kolkata business calendar.
 */

// --- Rules (Settings → Incentives) ----------------------------------------

/** One slab: reaching {@code minSales} in a day earns {@code bonus}. */
export interface IncentiveTier {
  minSales: number;
  bonus: number;
}

export type RuleVersionStatus = 'ACTIVE' | 'SCHEDULED' | 'PREVIOUS' | 'REPLACED';

/** One saved version of the rules; versions are never edited, only added. */
export interface IncentiveRuleVersion {
  id: number;
  effectiveFrom: string;
  tiers: IncentiveTier[];
  note?: string | null;
  /** Plain-language description of what this version changed. */
  changeSummary?: string | null;
  createdById?: number | null;
  createdByName?: string | null;
  createdAt: string;
  status: RuleVersionStatus;
}

export interface IncentiveRules {
  today: string;
  /** The version in force today, or null when none has started. */
  inForce: IncentiveRuleVersion | null;
  /** Every version, newest first. */
  versions: IncentiveRuleVersion[];
}

export interface IncentiveRulesRequest {
  effectiveFrom: string;
  /** An empty list switches the daily bonus off from {@code effectiveFrom}. */
  tiers: IncentiveTier[];
  note?: string | null;
}

// --- Month tracker --------------------------------------------------------

export interface TrackerDayInfo {
  date: string;
  /** MON..SUN */
  weekday: string;
  sunday: boolean;
  future: boolean;
}

export interface TrackerDayCell {
  date: string;
  orders: number;
  sales: number;
  bonus: number;
  /** Minimum sales of the slab that earned the bonus, or null. */
  slab: number | null;
  /** This is (one of) the person's highest non-Sunday days. */
  highest: boolean;
  /** This is (one of) the person's lowest non-Sunday selling days. */
  lowest: boolean;
}

export interface TrackerDayRef {
  date: string;
  sales: number;
}

export interface TrackerPerson {
  personId: number;
  name: string;
  active: boolean;
  days: TrackerDayCell[];
  orders: number;
  sales: number;
  bonus: number;
  bonusDays: number;
  sellingDays: number;
  /** Monday to Saturday days up to today with no order. */
  noSalesDays: number;
  /** Counted in totals and bonus, never used for the highest and lowest day. */
  sundaySales: number;
  averagePerSellingDay: number;
  /** Several entries when days tie. */
  highest: TrackerDayRef[];
  lowest: TrackerDayRef[];
}

export interface TrackerDayTotal {
  date: string;
  orders: number;
  sales: number;
  bonus: number;
}

export interface TrackerTotals {
  orders: number;
  sales: number;
  bonus: number;
  bonusDays: number;
}

export interface IncentiveTracker {
  /** yyyy-MM */
  month: string;
  monthStart: string;
  monthEnd: string;
  today: string;
  days: TrackerDayInfo[];
  people: TrackerPerson[];
  dayTotals: TrackerDayTotal[];
  totals: TrackerTotals;
  /** Rule versions that applied on at least one day of the month. */
  rules: IncentiveRuleVersion[];
  rulesDefined: boolean;
}

// --- Day-by-day product report -------------------------------------------

export interface ProductRow {
  date: string;
  personId: number;
  personName: string;
  productName: string;
  quantity: number;
  sales: number;
  orders: number;
}

export interface DayReconciliation {
  date: string;
  personId: number;
  personName: string;
  orders: number;
  dayTotal: number;
  lineTotal: number;
  /** Day total minus product lines: order-level discount and rupee round-off. */
  adjustment: number;
}

export interface ProductTotal {
  productName: string;
  quantity: number;
  sales: number;
}

export interface ProductReport {
  from: string;
  to: string;
  personId: number | null;
  rows: ProductRow[];
  days: DayReconciliation[];
  products: ProductTotal[];
  totals: { orders: number; quantity: number; lineTotal: number; dayTotal: number };
}

export type IncentiveReportKind = 'tracker' | 'calendar' | 'products';
export type IncentiveReportFormat = 'xlsx' | 'pdf';
