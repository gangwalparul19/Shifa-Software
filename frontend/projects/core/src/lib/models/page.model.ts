/**
 * Standard paged-response envelope returned by the backend's paginated
 * endpoints (Spring {@code Page} projection). Mirrors the shape
 * {@code { content, page, size, totalElements, totalPages }}.
 *
 * <p>Common request params for these endpoints are {@code page} (0-based),
 * {@code size} (default 20), and {@code sort=field,dir}.
 */
export interface PageResponse<T> {
  /** The rows on the current page. */
  content: T[];
  /** Zero-based index of the current page. */
  page: number;
  /** Requested page size. */
  size: number;
  /** Total number of rows across all pages. */
  totalElements: number;
  /** Total number of pages available. */
  totalPages: number;
}

/** Sort direction for a sortable column. */
export type SortDir = 'asc' | 'desc';

/** Active sort state for a paged table: a field plus a direction. */
export interface SortState {
  field: string;
  dir: SortDir;
}
