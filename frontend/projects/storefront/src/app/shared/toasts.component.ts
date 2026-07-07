import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/**
 * Host for the global toast queue (Phase 1.3 UI/UX). Rendered once in the app
 * shell, it binds to {@link ToastService.toasts} and stacks each notification
 * bottom-centre. The container is an {@code aria-live="polite"} /
 * {@code role="status"} region so screen readers announce confirmations without
 * stealing focus. Each toast has a manual dismiss control. Entry animation is
 * suppressed under {@code prefers-reduced-motion}.
 */
@Component({
  selector: 'sf-toasts',
  template: `
    <div class="sf-toasts" role="status" aria-live="polite" aria-atomic="false">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="sf-toast" [attr.data-variant]="toast.variant">
          <span class="sf-toast-icon" aria-hidden="true">
            {{ toast.variant === 'success' ? '✓' : toast.variant === 'error' ? '!' : 'ℹ' }}
          </span>
          <span class="sf-toast-msg">{{ toast.message }}</span>
          <button
            type="button"
            class="sf-toast-close"
            (click)="toasts.dismiss(toast.id)"
            aria-label="Dismiss notification"
          >
            ✕
          </button>
        </div>
      }
    </div>
  `,
  styleUrl: './toasts.component.css',
})
export class ToastsComponent {
  protected readonly toasts = inject(ToastService);
}
