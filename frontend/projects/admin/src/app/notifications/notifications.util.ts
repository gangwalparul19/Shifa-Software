/**
 * Formats an ISO timestamp as a short relative time (e.g. "just now", "5m",
 * "3h", "2d"), falling back to a date for anything older than a week. Used by
 * the notifications center and the top-bar bell dropdown.
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
  return new Date(then).toLocaleDateString();
}

/** Maps a notification severity to a Tabler text/border accent colour class. */
export function severityColor(severity: string): string {
  switch (severity) {
    case 'success':
      return 'text-success';
    case 'warning':
      return 'text-warning';
    case 'danger':
      return 'text-danger';
    default:
      return 'text-info';
  }
}

/** Maps a notification severity to a representative Tabler icon. */
export function severityIcon(severity: string): string {
  switch (severity) {
    case 'success':
      return 'ti-circle-check';
    case 'warning':
      return 'ti-alert-triangle';
    case 'danger':
      return 'ti-alert-octagon';
    default:
      return 'ti-info-circle';
  }
}
