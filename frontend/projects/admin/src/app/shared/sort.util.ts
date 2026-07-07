import { SortDir, SortState } from 'core';

/**
 * Pure helpers for driving server-side column sorting on paged tables.
 *
 * <p>A {@link SortState} is `{ field, dir }`. Clicking a column header toggles
 * the direction when it is already the active field, otherwise it selects the
 * new field starting ascending. The {@link sortParam} helper renders the
 * `sort=field,dir` query value the backend expects.
 */

/** Returns the next sort state after clicking {@link field}. */
export function toggleSort(current: SortState, field: string): SortState {
  if (current.field === field) {
    return { field, dir: current.dir === 'asc' ? 'desc' : 'asc' };
  }
  return { field, dir: 'asc' };
}

/** Renders the `field,dir` value for the `sort` query parameter. */
export function sortParam(sort: SortState): string {
  return `${sort.field},${sort.dir}`;
}

/** The arrow indicator to show for a column, given the active sort state. */
export function sortIndicator(sort: SortState, field: string): 'asc' | 'desc' | null {
  return sort.field === field ? sort.dir : null;
}

export type { SortDir, SortState };
