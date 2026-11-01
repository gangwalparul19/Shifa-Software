import { TrackerDayCell, TrackerDayInfo } from './incentives.model';

/**
 * Pure helpers for the daily sales tracker. Months are yyyy-MM and days yyyy-MM-dd in the
 * Asia/Kolkata calendar; nothing here reads the clock or the time zone, so results are the same
 * on every device.
 */

const MONTH_ONLY = /^(\d{4})-(\d{2})$/;
const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** Splits yyyy-MM, or returns null when the text is not a real month. */
function parseMonth(month: string): { year: number; month: number } | null {
  const m = MONTH_ONLY.exec(month);
  if (!m) {
    return null;
  }
  const year = Number(m[1]);
  const mo = Number(m[2]);
  return mo >= 1 && mo <= 12 ? { year, month: mo } : null;
}

/** The month a yyyy-MM-dd date falls in. */
export function monthOf(date: string): string {
  return date.slice(0, 7);
}

/** The month {@code delta} months after (or, if negative, before) the given one. */
export function addMonths(month: string, delta: number): string {
  const p = parseMonth(month);
  if (!p) {
    return month;
  }
  const index = p.year * 12 + (p.month - 1) + delta;
  const year = Math.floor(index / 12);
  const mo = (index % 12) + 1;
  return `${year}-${String(mo).padStart(2, '0')}`;
}

/** "November 2026". */
export function monthLabel(month: string): string {
  const p = parseMonth(month);
  return p ? `${MONTH_NAMES[p.month - 1]} ${p.year}` : month;
}

/** The first and last day of a month, as yyyy-MM-dd. */
export function monthRange(month: string): { from: string; to: string } {
  const p = parseMonth(month);
  if (!p) {
    return { from: month, to: month };
  }
  const last = new Date(Date.UTC(p.year, p.month, 0)).getUTCDate();
  const mm = String(p.month).padStart(2, '0');
  return { from: `${p.year}-${mm}-01`, to: `${p.year}-${mm}-${String(last).padStart(2, '0')}` };
}

/** "5 Nov" from 2026-11-05. */
export function shortDate(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) {
    return date;
  }
  return `${Number(m[3])} ${MONTH_NAMES[Number(m[2]) - 1].slice(0, 3)}`;
}

/** "Thu" for a yyyy-MM-dd date. */
export function weekdayShort(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) {
    return '';
  }
  const dow = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))).getUTCDay();
  return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][dow];
}

/** One square of the month calendar; {@code null} fields mark the blank squares around the month. */
export interface CalendarSquare {
  info: TrackerDayInfo | null;
  cell: TrackerDayCell | null;
}

/**
 * The month as weeks (Monday first, so Sunday is the last column), padded with blank squares so every
 * week has seven. {@code days} and {@code cells} are the same length and in date order.
 */
export function calendarWeeks(days: TrackerDayInfo[], cells: TrackerDayCell[]): CalendarSquare[][] {
  if (days.length === 0) {
    return [];
  }
  const squares: CalendarSquare[] = [];
  // JavaScript counts Sunday as 0; shift so Monday is 0 and Sunday is 6.
  const lead = (new Date(`${days[0].date}T00:00:00Z`).getUTCDay() + 6) % 7;
  for (let i = 0; i < lead; i++) {
    squares.push({ info: null, cell: null });
  }
  days.forEach((info, i) => squares.push({ info, cell: cells[i] ?? null }));
  while (squares.length % 7 !== 0) {
    squares.push({ info: null, cell: null });
  }
  const weeks: CalendarSquare[][] = [];
  for (let i = 0; i < squares.length; i += 7) {
    weeks.push(squares.slice(i, i + 7));
  }
  return weeks;
}
