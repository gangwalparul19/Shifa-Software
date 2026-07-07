import { Injectable, signal } from '@angular/core';

/** Options for a confirmation prompt. */
export interface ConfirmOptions {
  /** Dialog heading. */
  title: string;
  /** Body message explaining the consequence of the action. */
  message: string;
  /** Label for the confirm button (default "Confirm"). */
  confirmLabel?: string;
  /** Label for the cancel button (default "Cancel"). */
  cancelLabel?: string;
  /** When true, the confirm button is styled as a destructive (red) action. */
  danger?: boolean;
  /** Optional Tabler icon class shown next to the title (e.g. "ti-trash"). */
  icon?: string;
}

/** Internal shape held while a prompt is open. */
interface PendingConfirm {
  options: Required<Pick<ConfirmOptions, 'title' | 'message' | 'confirmLabel' | 'cancelLabel'>> &
    ConfirmOptions;
  resolve: (result: boolean) => void;
}

/**
 * A tiny signal-based confirmation service backing {@link ConfirmDialogComponent}.
 * Any component can await a user's decision before a destructive/irreversible
 * action:
 *
 * <pre>
 *   if (await this.confirm.confirm({ title: 'Delete coupon', message: '…', danger: true })) {
 *     // proceed
 *   }
 * </pre>
 *
 * A single dialog instance (mounted once at the app root) observes {@link pending}
 * and renders when a request is active.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  /** The currently open request, or null when no dialog is showing. */
  readonly pending = signal<PendingConfirm | null>(null);

  /** Opens the confirmation dialog, resolving true (confirm) or false (cancel). */
  confirm(options: ConfirmOptions): Promise<boolean> {
    // If a prompt is already open, cancel it first so we never stack dialogs.
    this.pending()?.resolve(false);
    return new Promise<boolean>((resolve) => {
      this.pending.set({
        options: {
          confirmLabel: 'Confirm',
          cancelLabel: 'Cancel',
          ...options,
        },
        resolve,
      });
    });
  }

  /** Resolve the open prompt (used by the dialog component). */
  settle(result: boolean): void {
    const current = this.pending();
    if (current) {
      this.pending.set(null);
      current.resolve(result);
    }
  }
}
