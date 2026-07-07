/**
 * Pure helpers for rendering a 1..5 star rating (Phase C). Kept framework-free
 * in core so both the storefront display component and the admin queue can share
 * the exact same star maths.
 */

/** The visual state of a single star in a 5-star row. */
export type StarState = 'full' | 'half' | 'empty';

/** Total stars in a rating row. */
export const MAX_STARS = 5;

/**
 * Maps an average rating (0..5, may be null/undefined) to five star states,
 * rounding to the nearest half star. A missing/zero rating yields five empty
 * stars. Values are clamped into [0, 5] so out-of-range input never breaks the
 * row.
 *
 * Examples: 4.5 → [full, full, full, full, half]; 3 → [full×3, empty×2].
 */
export function starStates(average: number | null | undefined): StarState[] {
  const value = clamp(average ?? 0);
  const rounded = Math.round(value * 2) / 2; // nearest half
  const states: StarState[] = [];
  for (let i = 1; i <= MAX_STARS; i++) {
    if (rounded >= i) {
      states.push('full');
    } else if (rounded >= i - 0.5) {
      states.push('half');
    } else {
      states.push('empty');
    }
  }
  return states;
}

/** Whole number of filled stars for a rating (nearest half rounded down to whole for solid fill). */
export function filledStars(average: number | null | undefined): number {
  return starStates(average).filter((s) => s === 'full').length;
}

/** Formats an average to one decimal for display (e.g. 4 → "4.0"), or '' when absent. */
export function formatAverage(average: number | null | undefined): string {
  if (average === null || average === undefined || Number.isNaN(average)) {
    return '';
  }
  return clamp(average).toFixed(1);
}

function clamp(value: number): number {
  if (Number.isNaN(value) || value < 0) {
    return 0;
  }
  return value > MAX_STARS ? MAX_STARS : value;
}
