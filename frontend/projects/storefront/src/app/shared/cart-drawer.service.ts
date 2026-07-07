import { Injectable, signal } from '@angular/core';

/**
 * Controls the slide-in cart drawer's open/closed state (Phase 1.3 UI/UX).
 *
 * <p>Kept as a tiny standalone service so the header (which triggers it) and the
 * drawer component (rendered in the app shell) share a single source of truth
 * without a parent/child binding.
 */
@Injectable({ providedIn: 'root' })
export class CartDrawerService {
  private readonly _open = signal(false);

  /** True while the cart drawer is visible. */
  readonly open = this._open.asReadonly();

  openDrawer(): void {
    this._open.set(true);
  }

  close(): void {
    this._open.set(false);
  }

  toggle(): void {
    this._open.update((open) => !open);
  }
}
