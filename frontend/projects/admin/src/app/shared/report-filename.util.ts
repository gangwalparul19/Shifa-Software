/**
 * The single naming convention for every downloaded report, so a file is
 * recognisable the moment it lands in the Downloads folder and nobody has to
 * rename it:
 *
 *   shifa-<report-name>_<date range>.<ext>
 *   e.g. shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf
 *
 * The server names the files it generates (`ReportFilenames` in the backend, the
 * source of truth). This mirrors its rules for the two cases where the browser
 * has to pick a name itself: CSVs the app builds client-side, and the fallback
 * when a response's `Content-Disposition` header is unavailable. Both sides are
 * tested against the same examples.
 *
 * Names use only `[a-z0-9._-]`, so they are valid on every operating system.
 */

const PREFIX = 'shifa';
const MAX_SLUG_LENGTH = 80;
const DEFAULT_SLUG = 'report';
const DEFAULT_EXTENSION = 'bin';

/** A lowercase, dash-separated ASCII form of `text`; `fallback` when nothing usable is left. */
export function slugify(text: string | null | undefined, fallback = DEFAULT_SLUG): string {
  if (text == null) {
    return fallback;
  }
  let slug = text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (slug.length > MAX_SLUG_LENGTH) {
    slug = slug.slice(0, MAX_SLUG_LENGTH).replace(/-+$/g, '');
  }
  return slug || fallback;
}

/**
 * How the covered dates read in a filename (ISO `yyyy-MM-dd` inputs):
 * `2026-10-02_to_2026-10-31`, a single `2026-10-02`, `from-2026-10-02`,
 * `up-to-2026-10-31`, or `all-time` when neither bound is set.
 */
export function rangePart(from: string | null | undefined, to: string | null | undefined): string {
  const start = from?.trim() || null;
  const end = to?.trim() || null;
  if (!start && !end) {
    return 'all-time';
  }
  if (start && end) {
    return start === end ? start : `${start}_to_${end}`;
  }
  return start ? `from-${start}` : `up-to-${end}`;
}

function cleanExtension(extension: string | null | undefined): string {
  const cleaned = (extension ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');
  return cleaned || DEFAULT_EXTENSION;
}

/** `shifa-<slug of title>_<label>.<extension>` for a caller-chosen label (e.g. "as-of-2026-10-31"). */
export function labelledFilename(
  title: string | null | undefined,
  label: string,
  extension: string,
): string {
  return `${PREFIX}-${slugify(title)}_${slugify(label, 'file')}.${cleanExtension(extension)}`;
}

/** `shifa-<slug of title>_<date range>.<extension>`, matching the server's report downloads. */
export function reportFilename(
  title: string | null | undefined,
  from: string | null | undefined,
  to: string | null | undefined,
  extension: string,
): string {
  return `${PREFIX}-${slugify(title)}_${rangePart(from, to)}.${cleanExtension(extension)}`;
}

/** A point-in-time list (no date range): `shifa-<slug of title>_as-of-<date>.<extension>`. */
export function asOfFilename(
  title: string | null | undefined,
  asOfDate: string,
  extension: string,
): string {
  return labelledFilename(title, `as-of-${asOfDate}`, extension);
}
