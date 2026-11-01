import { addIstDays, todayIst } from './business-date.util';

/**
 * The period choices shared by the Leaderboard and the Salespeople screens. The keys are the
 * backend's `PerformancePeriod` names, so a selection can be sent as-is. Every date is a calendar
 * day (yyyy-MM-dd) in the Asia/Kolkata business timezone; the server turns a named period into a
 * range itself, so the browser's clock and timezone never decide what "Yesterday" means.
 */
export type PeriodChoice =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'MONTH'
  | 'LAST_MONTH'
  | 'OVERALL'
  | 'CUSTOM';

/** A chosen period. `from` / `to` are only meaningful (and only sent) for `CUSTOM`. */
export interface PeriodSelection {
  period: PeriodChoice;
  from: string;
  to: string;
}

export interface PeriodOption {
  key: PeriodChoice;
  label: string;
}

/** The options offered, in display order. */
export const PERIOD_OPTIONS: readonly PeriodOption[] = [
  { key: 'TODAY', label: 'Today' },
  { key: 'YESTERDAY', label: 'Yesterday' },
  { key: 'LAST_7_DAYS', label: 'Last 7 days' },
  { key: 'MONTH', label: 'This month' },
  { key: 'LAST_MONTH', label: 'Last month' },
  { key: 'OVERALL', label: 'Overall' },
  { key: 'CUSTOM', label: 'Custom' },
];

/** The default everywhere: the current month. */
export const THIS_MONTH: PeriodSelection = { period: 'MONTH', from: '', to: '' };

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** Short label for a selection, e.g. "Last 7 days" or "2026-10-01 → 2026-10-10". */
export function periodLabel(selection: PeriodSelection): string {
  if (selection.period === 'CUSTOM') {
    return selection.from && selection.to ? `${selection.from} → ${selection.to}` : 'Custom';
  }
  return PERIOD_OPTIONS.find((o) => o.key === selection.period)?.label ?? selection.period;
}

/**
 * A plain-language problem with a custom range, or null when it is usable. Only the order of the
 * two days is checked: any length is accepted.
 */
export function customRangeProblem(from: string, to: string): string | null {
  if (!DATE_ONLY.test(from) || !DATE_ONLY.test(to)) {
    return 'Choose both a start and an end date.';
  }
  // yyyy-MM-dd strings sort the same way the dates do.
  return from > to ? 'The start date must not be after the end date.' : null;
}

/**
 * The first and last day (yyyy-MM-dd, both included) a selection stands for, in IST, or null for
 * "Overall". The server resolves the same periods itself; this lets a screen pass the matching
 * dates on to another page (for example the Orders filter).
 */
export function periodDates(
  selection: PeriodSelection,
  today: string = todayIst(),
): { from: string; to: string } | null {
  switch (selection.period) {
    case 'TODAY':
      return { from: today, to: today };
    case 'YESTERDAY': {
      const yesterday = addIstDays(today, -1);
      return { from: yesterday, to: yesterday };
    }
    case 'LAST_7_DAYS':
      return { from: addIstDays(today, -6), to: today };
    case 'MONTH':
      return { from: `${today.slice(0, 7)}-01`, to: today };
    case 'LAST_MONTH': {
      const lastDay = addIstDays(`${today.slice(0, 7)}-01`, -1);
      return { from: `${lastDay.slice(0, 7)}-01`, to: lastDay };
    }
    case 'CUSTOM':
      return isUsableSelection(selection) ? { from: selection.from, to: selection.to } : null;
    case 'OVERALL':
    default:
      return null;
  }
}

/** True when the selection can be sent to the server (a custom range needs two valid days). */
export function isUsableSelection(selection: PeriodSelection): boolean {
  return selection.period !== 'CUSTOM' || customRangeProblem(selection.from, selection.to) === null;
}

/** The query parameters for a selection: `period`, plus `from` / `to` for a custom range. */
export function periodQuery(selection: PeriodSelection): Record<string, string> {
  const query: Record<string, string> = { period: selection.period };
  if (selection.period === 'CUSTOM') {
    query['from'] = selection.from;
    query['to'] = selection.to;
  }
  return query;
}
