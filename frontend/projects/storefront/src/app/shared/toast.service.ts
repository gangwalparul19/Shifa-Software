import { Injectable, signal } from '@angular/core';

/** Visual variant for a toast — drives its icon and accent colour. */
export type ToastVariant = 'success' | 'error' | 'info';

/** A single transient notification shown by the {@link ToastService}. */
export interface Toast {
  readonly id: number;
  readonly message: string;
  readonly variant: ToastVariant;
}

/** Default auto-dismiss window (ms) for a toast. */
const DEFAULT_TIMEOUT_MS = 3000;

/**
 * Global, signals-based toast/confirmation queue (Phase 1.3 UI/UX).
 *
 * <p>Any feature can surface a brief confirmation ("Added to cart", "Saved to
 * wishlist", "Coupon applied") or an error via {@link success} / {@link error}
 * / {@link show}. A single host component ({@code <sf-toasts>}) is rendered once
 * in the app shell and binds to {@link toasts}; it renders an {@code aria-live}
 * region so assistive tech announces messages. Toasts auto-dismiss after a
 * sensible timeout and can be dismissed early via {@link dismiss}.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly _toasts = signal<Toast[]>([]);
  private nextId = 1;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();

  /** The live toast queue (read-only signal) the host component binds to. */
  readonly toasts = this._toasts.asReadonly();

  /** Queues a success confirmation (auto-dismisses). */
  success(message: string, timeout = DEFAULT_TIMEOUT_MS): number {
    return this.show(message, 'success', timeout);
  }

  /** Queues an error notification (slightly longer default so it can be read). */
  error(message: string, timeout = DEFAULT_TIMEOUT_MS + 1500): number {
    return this.show(message, 'error', timeout);
  }

  /**
   * Queues a toast of the given variant. A non-positive timeout keeps the toast
   * sticky until it is dismissed manually. Returns the toast id.
   */
  show(message: string, variant: ToastVariant = 'info', timeout = DEFAULT_TIMEOUT_MS): number {
    const id = this.nextId++;
    this._toasts.update((list) => [...list, { id, message, variant }]);
    if (timeout > 0 && typeof setTimeout === 'function') {
      this.timers.set(
        id,
        setTimeout(() => this.dismiss(id), timeout),
      );
    }
    return id;
  }

  /** Removes a toast (and clears its pending auto-dismiss timer). */
  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }
    this._toasts.update((list) => list.filter((toast) => toast.id !== id));
  }
}
