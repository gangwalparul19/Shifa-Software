import { Injectable, inject, signal } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiClient } from 'core';

/** Server-side push availability + this browser's subscription state. */
export type PushState = 'unsupported' | 'unconfigured' | 'available' | 'subscribed' | 'denied';

interface PublicKeyResponse {
  enabled: boolean;
  publicKey: string;
}

/**
 * Browser Web Push opt-in for staff (FEATURE-ROADMAP §8.3), built on Angular's
 * {@link SwPush}. Fetches the server VAPID public key, subscribes/unsubscribes
 * the device, and routes notification clicks to the linked order.
 *
 * <p>Gracefully degrades: {@code unsupported} when there is no service worker
 * (dev / unsupported browser) and {@code unconfigured} when the server has no
 * VAPID keys — in both cases the opt-in is simply hidden.
 */
@Injectable({ providedIn: 'root' })
export class PushNotificationsService {
  private readonly swPush = inject(SwPush);
  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);

  readonly state = signal<PushState>('unsupported');

  private publicKey = '';

  constructor() {
    if (!this.swPush.isEnabled) {
      this.state.set('unsupported');
      return;
    }
    // Deep-link when the user taps a push notification.
    this.swPush.notificationClicks.subscribe(({ notification }) => {
      const url = (notification as { data?: { url?: string } }).data?.url;
      if (url) {
        void this.router.navigateByUrl(url);
      }
    });
    void this.refresh();
  }

  /** Re-evaluates whether push is offered and whether this device is subscribed. */
  async refresh(): Promise<void> {
    if (!this.swPush.isEnabled) {
      this.state.set('unsupported');
      return;
    }
    try {
      const res = await firstValueFrom(
        this.api.get<PublicKeyResponse>('/api/notifications/push/public-key'),
      );
      if (!res.enabled || !res.publicKey) {
        this.state.set('unconfigured');
        return;
      }
      this.publicKey = res.publicKey;
      if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
        this.state.set('denied');
        return;
      }
      const sub = await firstValueFrom(this.swPush.subscription);
      this.state.set(sub ? 'subscribed' : 'available');
    } catch {
      this.state.set('unconfigured');
    }
  }

  /** Subscribes this browser and registers it with the server. */
  async enable(): Promise<void> {
    if (!this.swPush.isEnabled || !this.publicKey) {
      return;
    }
    try {
      const sub = await this.swPush.requestSubscription({ serverPublicKey: this.publicKey });
      await firstValueFrom(this.api.post('/api/notifications/push/subscribe', sub.toJSON()));
      this.state.set('subscribed');
    } catch {
      if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
        this.state.set('denied');
      }
    }
  }

  /** Unsubscribes this browser and removes it from the server. */
  async disable(): Promise<void> {
    try {
      const sub = await firstValueFrom(this.swPush.subscription);
      if (sub) {
        await firstValueFrom(
          this.api.post('/api/notifications/push/unsubscribe', { endpoint: sub.endpoint }),
        );
        await this.swPush.unsubscribe();
      }
      this.state.set('available');
    } catch {
      /* leave state as-is on failure */
    }
  }
}
