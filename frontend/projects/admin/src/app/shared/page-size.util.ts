/**
 * Tiny helpers to persist a table's chosen page size in {@code localStorage},
 * keyed per table (e.g. "orders", "products", "receivables"). Falls back to the
 * provided default when storage is unavailable or empty.
 */
const PREFIX = 'shifa.admin.pageSize.';

/** Reads the saved page size for {@link table}, or {@link fallback}. */
export function readPageSize(table: string, fallback = 10): number {
  try {
    const raw = localStorage.getItem(PREFIX + table);
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) && n > 0 ? n : fallback;
  } catch {
    return fallback;
  }
}

/** Persists the page size for {@link table}; ignores storage failures. */
export function writePageSize(table: string, size: number): void {
  try {
    localStorage.setItem(PREFIX + table, String(size));
  } catch {
    /* storage may be unavailable (private mode) — non-fatal */
  }
}
