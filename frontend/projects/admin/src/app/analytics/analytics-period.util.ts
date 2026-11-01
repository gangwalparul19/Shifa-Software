/**
 * Period choices for the Analytics page.
 *
 * Every date is a calendar day in the Asia/Kolkata business timezone, written yyyy-MM-dd, so the
 * same preset gives the same range on every device. The server accepts at most
 * {@link MAX_PERIOD_DAYS} days for the reports and {@link MAX_DATASET_DAYS} for the data downloads.
 */

export type PeriodKey = '7d' | '30d' | '90d' | 'month' | 'lastMonth' | 'fy' | '12m' | 'custom';

export interface PeriodPreset {
  key: PeriodKey;
  label: string;
}

export interface PeriodRange {
  from: string;
  to: string;
}

/** Longest period the report endpoints accept. */
export const MAX_PERIOD_DAYS = 731;
/** Longest period a dataset download accepts. */
export const MAX_DATASET_DAYS = 366;

export const PERIOD_PRESETS: readonly PeriodPreset[] = [
  { key: '7d', label: 'Last 7 days' },
  { key: '30d', label: 'Last 30 days' },
  { key: '90d', label: 'Last 90 days' },
  { key: 'month', label: 'This month' },
  { key: 'lastMonth', label: 'Last month' },
  { key: 'fy', label: 'This financial year' },
  { key: '12m', label: 'Last 12 months' },
  { key: 'custom', label: 'Custom' },
];

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Today's calendar date in IST as yyyy-MM-dd. */
export function istToday(now: Date = new Date()): string {
  // The en-CA locale formats a date as yyyy-MM-dd.
  return now.toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
}

/** A calendar day as a UTC midnight timestamp, or null when the text is not a real date. */
function toUtcDay(date: string): number | null {
  const m = DATE_ONLY.exec(date);
  if (!m) {
    return null;
  }
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  const t = Date.UTC(y, mo - 1, d);
  const check = new Date(t);
  // Reject 2026-02-31 and the like: JavaScript would roll them into the next month.
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) {
    return null;
  }
  return t;
}

function fromUtcDay(t: number): string {
  const d = new Date(t);
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  return `${d.getUTCFullYear()}-${mm}-${dd}`;
}

/** The date `days` after (or, if negative, before) the given date. */
export function addDays(date: string, days: number): string {
  const t = toUtcDay(date);
  if (t === null) {
    return date;
  }
  return fromUtcDay(t + days * 86_400_000);
}

/** Days in the period, both ends included; 0 when either date is not a real date. */
export function inclusiveDays(from: string, to: string): number {
  const a = toUtcDay(from);
  const b = toUtcDay(to);
  if (a === null || b === null) {
    return 0;
  }
  return Math.round((b - a) / 86_400_000) + 1;
}

/**
 * The range a preset stands for, counted back from {@code today}. {@code 'custom'} has no fixed
 * range, so it returns today alone; the caller keeps the dates the user typed.
 */
export function resolvePeriod(key: PeriodKey, today: string): PeriodRange {
  const t = toUtcDay(today);
  if (t === null) {
    return { from: today, to: today };
  }
  const now = new Date(t);
  const year = now.getUTCFullYear();
  const month = now.getUTCMonth();
  switch (key) {
    case '7d':
      return { from: addDays(today, -6), to: today };
    case '30d':
      return { from: addDays(today, -29), to: today };
    case '90d':
      return { from: addDays(today, -89), to: today };
    case '12m':
      return { from: addDays(today, -364), to: today };
    case 'month':
      return { from: fromUtcDay(Date.UTC(year, month, 1)), to: today };
    case 'lastMonth':
      return {
        from: fromUtcDay(Date.UTC(year, month - 1, 1)),
        // Day 0 of this month is the last day of the previous one.
        to: fromUtcDay(Date.UTC(year, month, 0)),
      };
    case 'fy': {
      // The Indian financial year starts on 1 April.
      const startYear = month >= 3 ? year : year - 1;
      return { from: fromUtcDay(Date.UTC(startYear, 3, 1)), to: today };
    }
    case 'custom':
    default:
      return { from: today, to: today };
  }
}

/**
 * A plain-language problem with a typed period, or null when it is fine.
 * {@code maxDays} lets the data tab apply its tighter limit.
 */
export function periodProblem(from: string, to: string, maxDays: number = MAX_PERIOD_DAYS): string | null {
  if (toUtcDay(from) === null || toUtcDay(to) === null) {
    return 'Choose both a start and an end date.';
  }
  const days = inclusiveDays(from, to);
  if (days < 1) {
    return 'The start date must not be after the end date.';
  }
  if (days > maxDays) {
    return `Choose a period of at most ${maxDays} days.`;
  }
  return null;
}
