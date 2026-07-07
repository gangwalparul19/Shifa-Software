import { Injectable, signal } from '@angular/core';

/** The visual/semantic flavour of a toast. */
export type ToastKind = 'success' | 'error' | 'info';

/** A single queued toast notification. */
export interface ToastMessage {
  /** Monotonic id used for tracking and dismissal. */
  id: number;
  kind: ToastKind;
  text: string;
}

/**
 * A tiny signal-based toast queue for the admin app.
 *
 * <p>Any feature can surface optimistic success/error/info feedback without
 * owning its own markup — a single {@code ToastsComponent} host (mounted once in
 * the admin shell) renders whatever is in {@link toasts}. Toasts auto-dismiss
 * after a short delay; errors linger a little longer so they are not missed.
 *
 * <pre>
 *   this.toasts.success('User created');
 *   this.toasts.error('Username already taken');
 * </pre>
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  /** The live queue of visible toasts, oldest first. */
  readonly toasts = signal<ToastMessage[]>([]);

  private seq = 0;
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();

  /** Shows a success toast (green). */
  success(text: string, durationMs = 4000): number {
    return this.push('success', text, durationMs);
  }

  /** Shows an error toast (red); lingers longer by default so it is not missed. */
  error(text: string, durationMs = 6000): number {
    return this.push('error', text, durationMs);
  }

  /** Shows a neutral informational toast. */
  info(text: string, durationMs = 4000): number {
    return this.push('info', text, durationMs);
  }

  /** Removes a toast (used by the auto-dismiss timer and the close button). */
  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  /** Clears every toast (e.g. on route teardown if ever needed). */
  clear(): void {
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
    this.toasts.set([]);
  }

  private push(kind: ToastKind, text: string, durationMs: number): number {
    const id = ++this.seq;
    this.toasts.update((list) => [...list, { id, kind, text }]);
    if (durationMs > 0) {
      this.timers.set(
        id,
        setTimeout(() => this.dismiss(id), durationMs),
      );
    }
    return id;
  }
}
