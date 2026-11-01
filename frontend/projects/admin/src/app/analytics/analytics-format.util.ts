/** Small, pure display helpers shared by the Analytics tabs. */

export const WEEKDAYS_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
export const WEEKDAYS_LONG = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
] as const;

/** Percentage change from `previous` to `current`; null when there is nothing to compare with. */
export function changePct(current: number, previous: number): number | null {
  if (!Number.isFinite(current) || !Number.isFinite(previous) || previous <= 0) {
    return null;
  }
  return Math.round(((current - previous) * 1000) / previous) / 10;
}

/** "+12.5%", "-3.0%" or an em dash when there is no comparison. */
export function changeLabel(change: number | null): string {
  if (change === null) {
    return '—';
  }
  const sign = change > 0 ? '+' : '';
  return `${sign}${change.toFixed(1)}%`;
}

/**
 * Bootstrap text colour for a change: green when it moved the good way, red when it
 * moved the bad way, grey when flat or unknown. Pass `higherIsBetter = false` for
 * measures such as lost orders.
 */
export function changeTone(change: number | null, higherIsBetter = true): string {
  if (change === null || change === 0) {
    return 'text-secondary';
  }
  const good = higherIsBetter ? change > 0 : change < 0;
  return good ? 'text-success' : 'text-danger';
}

/** Arrow icon class (Tabler) for a change. */
export function changeIcon(change: number | null): string {
  if (change === null || change === 0) {
    return 'ti-minus';
  }
  return change > 0 ? 'ti-trending-up' : 'ti-trending-down';
}

/** "09:00" for hour 9. */
export function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

/** Hours as a short phrase: "45 min", "3.5 h" or "2.1 days"; an em dash when unknown. */
export function humanHours(hours: number | null | undefined): string {
  if (hours === null || hours === undefined || !Number.isFinite(hours)) {
    return '—';
  }
  if (hours < 1) {
    return `${Math.round(hours * 60)} min`;
  }
  if (hours < 48) {
    return `${Math.round(hours * 10) / 10} h`;
  }
  return `${Math.round((hours / 24) * 10) / 10} days`;
}

/** Splits a pointer's `actionRoute` ("/analytics?tab=customers") into a path and query params. */
export function splitRoute(route: string | null | undefined): { path: string; query: Record<string, string> } | null {
  if (!route) {
    return null;
  }
  const [path, queryString] = route.split('?');
  const query: Record<string, string> = {};
  if (queryString) {
    for (const pair of queryString.split('&')) {
      const [key, value] = pair.split('=');
      if (key) {
        query[decodeURIComponent(key)] = decodeURIComponent(value ?? '');
      }
    }
  }
  return { path, query };
}
