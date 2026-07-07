import fc from 'fast-check';
import { MAX_STARS, StarState, filledStars, formatAverage, starStates } from './star-rating.util';

/**
 * Property-based tests for the pure star-rating helpers (Phase C).
 *
 * These pin the invariants both apps rely on when rendering a rating row so the
 * star maths stays correct for any average value.
 */
describe('star-rating util (properties)', () => {
  it('always produces exactly five star states', () => {
    fc.assert(
      fc.property(fc.double({ min: -10, max: 20, noNaN: true }), (avg) => {
        expect(starStates(avg)).toHaveLength(MAX_STARS);
      }),
    );
  });

  it('star states never contradict their neighbours (non-increasing left to right)', () => {
    // A rating row fills from the left: a full star can never follow an empty one.
    const weight = (s: StarState): number => (s === 'full' ? 2 : s === 'half' ? 1 : 0);
    fc.assert(
      fc.property(fc.double({ min: 0, max: 5, noNaN: true }), (avg) => {
        const states = starStates(avg);
        for (let i = 1; i < states.length; i++) {
          expect(weight(states[i])).toBeLessThanOrEqual(weight(states[i - 1]));
        }
      }),
    );
  });

  it('clamps out-of-range input to [0, 5] stars', () => {
    fc.assert(
      fc.property(fc.double({ min: -100, max: 100, noNaN: true }), (avg) => {
        const full = filledStars(avg);
        expect(full).toBeGreaterThanOrEqual(0);
        expect(full).toBeLessThanOrEqual(MAX_STARS);
      }),
    );
  });

  it('formats a present average to one decimal, and absent values to empty string', () => {
    expect(formatAverage(null)).toBe('');
    expect(formatAverage(undefined)).toBe('');
    fc.assert(
      fc.property(fc.double({ min: 0, max: 5, noNaN: true }), (avg) => {
        expect(formatAverage(avg)).toMatch(/^\d\.\d$/);
      }),
    );
  });
});
