/**
 * Formats an ISO timestamp as a short relative time (e.g. "just now", "5m",
 * "3h", "2d"), falling back to a date for anything older than a week. Used by
 * the notifications center and the top-bar bell dropdown.
 *
 * <p>Re-exported from the shared {@code time.util} (enhancement: relative
 * timestamps) so other lists (Orders, Returns, RTO log, …) can reuse the exact
 * same formatting without duplicating it.
 */
export { relativeTime } from '../shared/time.util';

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
