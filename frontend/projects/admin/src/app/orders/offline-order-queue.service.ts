import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiClient } from 'core';
import { ToastService } from '../shared/toast.service';
import { CreateOrderRequest, OrderDetail } from './orders.model';

/** A salesperson order captured offline, awaiting sync when connectivity returns. */
export interface QueuedOrder {
  /** Client-generated id (the row has no server id until synced). */
  localId: string;
  payload: CreateOrderRequest;
  /** Cached for display in the pending list without re-parsing the payload. */
  customerName: string;
  customerMobile: string;
  /** Order total in paise, for display. */
  totalPaise: number;
  queuedAt: number;
}

const STORAGE_KEY = 'shifa.offlineOrders.v1';

/**
 * Offline order queue (FEATURE-ROADMAP §8.1). Lets a salesperson capture a
 * (COD, no-screenshot) order while offline; the payload is stored locally and
 * POSTed to {@code /api/orders} automatically when the browser reconnects.
 *
 * <p>Deliberately scoped to orders with no payment collected: an order that
 * takes money needs a screenshot upload, which requires connectivity anyway. The
 * queue is durable across reloads via {@code localStorage} and drains on the
 * {@code online} event and at startup.
 */
@Injectable({ providedIn: 'root' })
export class OfflineOrderQueueService {
  private readonly api = inject(ApiClient);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  /** Orders waiting to sync. */
  readonly pending = signal<QueuedOrder[]>(this.load());
  readonly pendingCount = computed(() => this.pending().length);

  private syncing = false;

  constructor() {
    if (typeof window !== 'undefined') {
      const onOnline = () => void this.flush();
      window.addEventListener('online', onOnline);
      this.destroyRef.onDestroy(() => window.removeEventListener('online', onOnline));
      // Drain anything left from a previous session once, shortly after start.
      if (navigator.onLine && this.pending().length > 0) {
        setTimeout(() => void this.flush(), 2000);
      }
    }
  }

  /** Adds an order to the offline queue and returns its local id. */
  enqueue(payload: CreateOrderRequest, totalPaise: number): string {
    const item: QueuedOrder = {
      localId: `ord-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      payload,
      customerName: payload.customerName,
      customerMobile: payload.customerMobile,
      totalPaise,
      queuedAt: Date.now(),
    };
    this.pending.update((list) => [...list, item]);
    this.persist();
    return item.localId;
  }

  /** Removes a queued order (e.g. the user discards it). */
  remove(localId: string): void {
    this.pending.update((list) => list.filter((o) => o.localId !== localId));
    this.persist();
  }

  /**
   * Attempts to POST every queued order in turn. Successfully synced orders are
   * removed; failures are kept for the next attempt. No-op when offline or when a
   * flush is already running.
   */
  async flush(): Promise<void> {
    if (this.syncing || (typeof navigator !== 'undefined' && !navigator.onLine)) {
      return;
    }
    const queue = this.pending();
    if (queue.length === 0) {
      return;
    }
    this.syncing = true;
    let synced = 0;
    try {
      for (const item of queue) {
        try {
          await firstValueFrom(this.api.post<OrderDetail>('/api/orders', item.payload));
          this.remove(item.localId);
          synced++;
        } catch {
          // Stop on the first failure (likely still offline / server down);
          // retry the rest on the next online event.
          break;
        }
      }
    } finally {
      this.syncing = false;
    }
    if (synced > 0) {
      this.toasts.success(
        `${synced} offline order${synced === 1 ? '' : 's'} synced to the server.`,
      );
    }
  }

  private load(): QueuedOrder[] {
    if (typeof localStorage === 'undefined') {
      return [];
    }
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as QueuedOrder[]) : [];
    } catch {
      return [];
    }
  }

  private persist(): void {
    if (typeof localStorage === 'undefined') {
      return;
    }
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.pending()));
    } catch {
      /* storage full / unavailable — non-fatal */
    }
  }
}
