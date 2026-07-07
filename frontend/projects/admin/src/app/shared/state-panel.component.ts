import { NgTemplateOutlet } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';

/** The presentational state a list/section is currently in. */
export type PanelVariant = 'loading' | 'empty' | 'error' | 'onboarding';

/**
 * A single presentational component for the three async states a list page can
 * be in, plus a first-run "onboarding" flavour of empty:
 *
 * <ul>
 *   <li><strong>loading</strong> — a shimmer skeleton (rows or cards);</li>
 *   <li><strong>empty</strong> — a friendly icon + message, with an optional CTA;</li>
 *   <li><strong>onboarding</strong> — like empty but framed as "add your first…"
 *       for a genuinely empty account;</li>
 *   <li><strong>error</strong> — a clear message with a Retry button.</li>
 * </ul>
 *
 * Wrap it in the page's card (or pass {@code [card]="true"} to render its own).
 */
@Component({
  selector: 'admin-state-panel',
  standalone: true,
  template: `
    @if (card) {
      <div class="card">
        <ng-container [ngTemplateOutlet]="content" />
      </div>
    } @else {
      <ng-container [ngTemplateOutlet]="content" />
    }

    <ng-template #content>
      @switch (variant) {
        @case ('loading') {
          <div class="card-body" role="status" [attr.aria-label]="loadingLabel">
            @if (skeleton === 'cards') {
              <div class="row row-cards">
                @for (s of skeletonRows; track s) {
                  <div class="col-md-6 col-lg-3">
                    <div class="skeleton skeleton-card mb-3"></div>
                    <div class="skeleton skeleton-line w-60"></div>
                  </div>
                }
              </div>
            } @else {
              @for (s of skeletonRows; track s) {
                <div class="skeleton skeleton-line w-80 mb-3"></div>
              }
            }
            <span class="visually-hidden">{{ loadingLabel }}</span>
          </div>
        }
        @case ('error') {
          <div class="empty">
            <div class="empty-icon text-danger"><i class="ti ti-alert-triangle" aria-hidden="true"></i></div>
            <p class="empty-title">{{ title || 'Something went wrong' }}</p>
            <p class="empty-subtitle text-secondary">{{ message || 'The request failed. Please try again.' }}</p>
            @if (retryable) {
              <div class="empty-action">
                <button type="button" class="btn btn-danger" (click)="retry.emit()">
                  <i class="ti ti-refresh me-1" aria-hidden="true"></i> {{ retryLabel }}
                </button>
              </div>
            }
          </div>
        }
        @default {
          <div class="empty">
            <div class="empty-icon" [class.text-primary]="variant === 'onboarding'">
              <i class="ti {{ icon }}" aria-hidden="true"></i>
            </div>
            <p class="empty-title">{{ title }}</p>
            @if (message) {
              <p class="empty-subtitle text-secondary">{{ message }}</p>
            }
            @if (ctaLabel) {
              <div class="empty-action">
                <button type="button" class="btn btn-primary" (click)="cta.emit()">
                  <i class="ti {{ ctaIcon }} me-1" aria-hidden="true"></i> {{ ctaLabel }}
                </button>
              </div>
            }
          </div>
        }
      }
    </ng-template>
  `,
  imports: [NgTemplateOutlet],
})
export class StatePanelComponent {
  @Input() variant: PanelVariant = 'empty';

  /** Render the panel inside its own card shell. */
  @Input() card = false;

  /** Icon (Tabler class, e.g. "ti-receipt-off") for empty/onboarding states. */
  @Input() icon = 'ti-inbox';

  @Input() title = 'Nothing here yet';
  @Input() message?: string;

  /** Loading skeleton style. */
  @Input() skeleton: 'rows' | 'cards' = 'rows';
  @Input() skeletonRows: number[] = [1, 2, 3, 4, 5];
  @Input() loadingLabel = 'Loading…';

  /** Error retry affordance. */
  @Input() retryable = true;
  @Input() retryLabel = 'Retry';

  /** Empty/onboarding CTA. */
  @Input() ctaLabel?: string;
  @Input() ctaIcon = 'ti-plus';

  @Output() retry = new EventEmitter<void>();
  @Output() cta = new EventEmitter<void>();
}
