/**
 * Formats an ISO timestamp as a short relative time (e.g. "just now", "5m",
 * "3h", "2d"), falling back to a date for anything older than a week.
 * Shared implementation (enhancement: relative timestamps) — originally lived
 * only in the notifications feature; moved here so any list (Orders, Returns,
 * RTO log, etc.) can show "X ago" alongside its exact date without
 * duplicating the logic.
 */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return '';
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return '';
  }
  const diffSec = Math.max(0, Math.round((now - then) / 1000));
  if (diffSec < 45) {
    return 'just now';
  }
  const diffMin = Math.round(diffSec / 60);
  if (diffMin < 60) {
    return `${diffMin}m ago`;
  }
  const diffHr = Math.round(diffMin / 60);
  if (diffHr < 24) {
    return `${diffHr}h ago`;
  }
  const diffDay = Math.round(diffHr / 24);
  if (diffDay < 7) {
    return `${diffDay}d ago`;
  }
  // Older than a week: show the date in IST (Asia/Kolkata) so it matches the rest
  // of the app regardless of the viewer's device timezone.
  return new Date(then).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata' });
}
