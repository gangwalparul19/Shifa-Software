/** Calendar-date helpers for the Asia/Kolkata business timezone. */
const BUSINESS_TIME_ZONE = 'Asia/Kolkata';

function partsOf(date: Date): Record<string, string> {
  return Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: BUSINESS_TIME_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  );
}

/** Returns today's IST calendar date as yyyy-MM-dd. */
export function todayIst(now = new Date()): string {
  const parts = partsOf(now);
  return `${parts['year']}-${parts['month']}-${parts['day']}`;
}

export function nowIstLocalMinute(now = new Date()): string {
  const values = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: BUSINESS_TIME_ZONE,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(now)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  );
  return `${values['year']}-${values['month']}-${values['day']}T${values['hour']}:${values['minute']}`;
}

/** Adds calendar days without passing through the browser's local timezone. */
export function addIstDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + days, 12));
  return date.toISOString().slice(0, 10);
}

/** First day of the current IST calendar month. */
export function monthStartIst(now = new Date()): string {
  return `${todayIst(now).slice(0, 7)}-01`;
}

/** Last day of the current IST calendar month. */
export function monthEndIst(now = new Date()): string {
  const firstOfNext = addIstDays(monthStartIst(now), 32);
  return addIstDays(`${firstOfNext.slice(0, 7)}-01`, -1);
}

/** Monday of the IST calendar week containing the supplied date. */
export function weekStartIst(isoDate = todayIst()): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day, 12));
  const dayOfWeek = date.getUTCDay();
  const mondayOffset = dayOfWeek === 0 ? -6 : 1 - dayOfWeek;
  return addIstDays(isoDate, mondayOffset);
}

/** Indian financial year start for the supplied current instant. */
export function financialYearStartIst(now = new Date()): string {
  const today = todayIst(now);
  const year = Number(today.slice(0, 4)) - (Number(today.slice(5, 7)) < 4 ? 1 : 0);
  return `${year}-04-01`;
}
