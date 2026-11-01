/** Options for {@link formatDateLabel}. */
export interface DateLabelOptions {
  /** Text shown when there is no value. Defaults to an em dash. */
  empty?: string;
  /** Day-of-month style. Defaults to `'2-digit'` ("05 Jan 2024"); `'numeric'` gives "5 Jan 2024". */
  day?: 'numeric' | '2-digit';
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Formats a calendar date or ISO timestamp as a short Indian-locale date
 * ("05 Jan 2024") in the Asia/Kolkata business timezone, so the same record
 * reads the same on every device.
 *
 * <ul>
 *   <li>A date-only value (`yyyy-MM-dd`, e.g. a date of birth) is a calendar day,
 *       not an instant: it is anchored at noon UTC before formatting, so no device
 *       timezone can shift it to the neighbouring day.</li>
 *   <li>A timestamp is an instant and is shown in IST.</li>
 *   <li>A missing value renders {@code options.empty} (default "—"); a value that
 *       cannot be parsed is returned unchanged rather than "Invalid Date".</li>
 * </ul>
 */
export function formatDateLabel(
  value: string | Date | null | undefined,
  options: DateLabelOptions = {},
): string {
  if (!value) {
    return options.empty ?? '—';
  }
  const dateOnly = typeof value === 'string' ? DATE_ONLY.exec(value) : null;
  const date = dateOnly
    ? new Date(Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]), 12))
    : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return typeof value === 'string' ? value : (options.empty ?? '—');
  }
  return date.toLocaleDateString('en-IN', {
    day: options.day ?? '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  });
}
