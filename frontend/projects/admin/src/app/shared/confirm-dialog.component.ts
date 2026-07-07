import {
  AfterViewChecked,
  Component,
  ElementRef,
  HostListener,
  ViewChild,
  computed,
  inject,
} from '@angular/core';
import { ConfirmService } from './confirm.service';

/**
 * The single confirmation dialog for the whole admin, driven by
 * {@link ConfirmService}. Mounted once at the app root so any feature can await
 * {@code confirm.confirm(...)} for destructive/irreversible actions.
 *
 * <p>Accessibility: the dialog uses {@code role="dialog"} + {@code aria-modal},
 * moves focus to the confirm button on open, traps Tab within the dialog, cancels
 * on Escape and on backdrop click, and returns focus to the triggering element
 * on close. Entry animation is suppressed under {@code prefers-reduced-motion}
 * by the global stylesheet.
 */
@Component({
  selector: 'admin-confirm-dialog',
  standalone: true,
  template: `
    @if (options(); as o) {
      <div class="shifa-overlay" (click)="cancel()"></div>
      <div
        #dialog
        class="shifa-modal shifa-confirm"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
        [attr.aria-describedby]="messageId"
        (keydown)="onKeydown($event)"
      >
        <div class="card">
          <div class="card-body">
            <div class="d-flex align-items-start gap-3">
              <span
                class="shifa-confirm__icon"
                [class.is-danger]="o.danger"
                aria-hidden="true"
              >
                <i class="ti {{ o.icon || (o.danger ? 'ti-alert-triangle' : 'ti-help-circle') }}"></i>
              </span>
              <div class="flex-fill">
                <h3 class="card-title mb-1" [id]="titleId">{{ o.title }}</h3>
                <p class="text-secondary mb-0" [id]="messageId">{{ o.message }}</p>
              </div>
            </div>
            <div class="d-flex justify-content-end gap-2 mt-4">
              <button type="button" #cancelBtn class="btn btn-outline-secondary" (click)="cancel()">
                {{ o.cancelLabel }}
              </button>
              <button
                type="button"
                #confirmBtn
                class="btn"
                [class.btn-danger]="o.danger"
                [class.btn-primary]="!o.danger"
                (click)="accept()"
              >
                {{ o.confirmLabel }}
              </button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
})
export class ConfirmDialogComponent implements AfterViewChecked {
  private readonly confirm = inject(ConfirmService);

  @ViewChild('dialog') private dialog?: ElementRef<HTMLElement>;
  @ViewChild('confirmBtn') private confirmBtn?: ElementRef<HTMLButtonElement>;

  protected readonly titleId = 'confirm-title';
  protected readonly messageId = 'confirm-message';

  protected readonly options = computed(() => this.confirm.pending()?.options ?? null);

  /** The element focused before the dialog opened, restored on close. */
  private trigger: HTMLElement | null = null;
  private focused = false;

  ngAfterViewChecked(): void {
    if (this.options() && !this.focused) {
      this.trigger = document.activeElement as HTMLElement | null;
      this.confirmBtn?.nativeElement.focus();
      this.focused = true;
    } else if (!this.options() && this.focused) {
      this.focused = false;
      this.trigger?.focus?.();
      this.trigger = null;
    }
  }

  protected accept(): void {
    this.confirm.settle(true);
  }

  protected cancel(): void {
    this.confirm.settle(false);
  }

  /** Escape cancels; Tab is trapped within the dialog. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
      return;
    }
    if (event.key === 'Tab') {
      this.trapTab(event);
    }
  }

  private trapTab(event: KeyboardEvent): void {
    const root = this.dialog?.nativeElement;
    if (!root) {
      return;
    }
    const focusable = Array.from(
      root.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => !el.hasAttribute('disabled'));
    if (focusable.length === 0) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement as HTMLElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  /** A global Escape guard in case focus escaped the dialog element. */
  @HostListener('document:keydown.escape')
  protected onDocumentEscape(): void {
    if (this.options()) {
      this.cancel();
    }
  }
}
