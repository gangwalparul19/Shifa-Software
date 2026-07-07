import { Component, computed, input } from '@angular/core';
import { formatAverage, starStates } from 'core';

/**
 * Reusable read-only star rating display (Phase C).
 *
 * <p>Renders five herbal-green stars (full/half/empty) for a numeric average and
 * optionally the numeric value + review count beside them. Used on product cards
 * (compact) and the product detail header (with count). Purely presentational —
 * the star maths lives in the shared core `starStates` helper.
 */
@Component({
  selector: 'sf-star-rating',
  standalone: true,
  template: `
    <span class="stars" [attr.aria-label]="ariaLabel()" role="img">
      @for (state of states(); track $index) {
        <span class="star" [attr.data-state]="state" aria-hidden="true">
          @if (state === 'half') {
            <span class="half">★</span><span class="base">★</span>
          } @else {
            ★
          }
        </span>
      }
      @if (showValue() && value() !== '') {
        <span class="value">{{ value() }}</span>
      }
      @if (count() !== null && count() !== undefined) {
        <span class="count">({{ count() }})</span>
      }
    </span>
  `,
  styles: [
    `
      .stars {
        display: inline-flex;
        align-items: center;
        gap: 0.1rem;
        line-height: 1;
        white-space: nowrap;
      }
      .star {
        position: relative;
        font-size: var(--sf-star-size, 1rem);
        color: #d8dcc7;
        letter-spacing: 0.02em;
      }
      .star[data-state='full'] {
        color: #f4b740;
      }
      .star .half {
        position: absolute;
        left: 0;
        top: 0;
        width: 50%;
        overflow: hidden;
        color: #f4b740;
      }
      .star .base {
        color: #d8dcc7;
      }
      :host-context(html[data-theme='dark']) .star,
      :host-context(html[data-theme='dark']) .star .base {
        color: var(--border);
      }
      :host-context(html[data-theme='dark']) .value {
        color: var(--herbal-green);
      }
      :host-context(html[data-theme='dark']) .count {
        color: var(--text-muted);
      }
      .value {
        margin-left: 0.35rem;
        font-weight: 600;
        font-size: 0.85em;
        color: #3f5d34;
      }
      .count {
        margin-left: 0.25rem;
        font-size: 0.8em;
        color: #7a8471;
      }
    `,
  ],
})
export class StarRatingComponent {
  /** The average rating to render (0..5, may be null/undefined → empty stars). */
  readonly rating = input<number | null | undefined>(0);
  /** Optional review count shown in parentheses; omit/null to hide. */
  readonly count = input<number | null | undefined>(null);
  /** Whether to show the numeric average next to the stars. */
  readonly showValue = input<boolean>(false);

  protected readonly states = computed(() => starStates(this.rating()));
  protected readonly value = computed(() => formatAverage(this.rating()));
  protected readonly ariaLabel = computed(() => {
    const v = this.value();
    const c = this.count();
    if (v === '') {
      return 'No ratings yet';
    }
    return c ? `Rated ${v} out of 5 from ${c} reviews` : `Rated ${v} out of 5`;
  });
}
