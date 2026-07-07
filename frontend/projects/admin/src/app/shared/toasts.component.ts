import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/**
 * The single toast host for the whole admin, driven by {@link ToastService}.
 * Mounted once in the admin shell so any feature can call
 * {@code toasts.success(...)} / {@code toasts.error(...)} without owning markup.
 *
 * <p>Accessibility: the stack is a polite live region ({@code role="status"} +
 * {@code aria-live="polite"}) so screen readers announce new toasts without
 * interrupting. Each toast has a manual dismiss button. Positioned top-right on
 * tablet/desktop and full-width across the top on phones (see the scoped CSS).
 */
@Component({
  selector: 'admin-toasts',
  standalone: true,
  template: `
    <div class="shifa-toasts" role="status" aria-live="polite" aria-atomic="false">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="shifa-toasts__item" [attr.data-kind]="toast.kind">
          <i
            class="ti shifa-toasts__icon"
            [class.ti-circle-check]="toast.kind === 'success'"
            [class.ti-alert-triangle]="toast.kind === 'error'"
            [class.ti-info-circle]="toast.kind === 'info'"
            aria-hidden="true"
          ></i>
          <span class="shifa-toasts__text">{{ toast.text }}</span>
          <button
            type="button"
            class="shifa-toasts__close"
            (click)="toasts.dismiss(toast.id)"
            aria-label="Dismiss notification"
          >
            <i class="ti ti-x" aria-hidden="true"></i>
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .shifa-toasts {
        position: fixed;
        top: 1rem;
        right: 1rem;
        z-index: 1090;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        width: min(360px, calc(100vw - 2rem));
        pointer-events: none;
      }
      .shifa-toasts__item {
        pointer-events: auto;
        display: flex;
        align-items: flex-start;
        gap: 0.6rem;
        padding: 0.75rem 0.85rem;
        border-radius: 0.6rem;
        color: #fff;
        background: var(--shifa-green-600, #1f5d3f);
        box-shadow: 0 12px 32px rgba(15, 51, 36, 0.24);
        animation: shifaSlideInRight 0.3s var(--shifa-ease, ease) both;
      }
      .shifa-toasts__item[data-kind='error'] {
        background: #b42318;
        box-shadow: 0 12px 32px rgba(180, 35, 24, 0.24);
      }
      .shifa-toasts__item[data-kind='info'] {
        background: #1c3d5a;
        box-shadow: 0 12px 32px rgba(28, 61, 90, 0.24);
      }
      .shifa-toasts__icon {
        font-size: 1.1rem;
        line-height: 1.4;
        flex: 0 0 auto;
      }
      .shifa-toasts__text {
        flex: 1 1 auto;
        font-size: 0.9rem;
        line-height: 1.4;
        word-break: break-word;
      }
      .shifa-toasts__close {
        flex: 0 0 auto;
        border: 0;
        background: transparent;
        color: inherit;
        opacity: 0.85;
        padding: 0;
        margin-left: 0.15rem;
        cursor: pointer;
        line-height: 1;
      }
      .shifa-toasts__close:hover {
        opacity: 1;
      }
      @media (max-width: 575.98px) {
        .shifa-toasts {
          top: 0.5rem;
          right: 0.5rem;
          left: 0.5rem;
          width: auto;
        }
      }
    `,
  ],
})
export class ToastsComponent {
  protected readonly toasts = inject(ToastService);
}
