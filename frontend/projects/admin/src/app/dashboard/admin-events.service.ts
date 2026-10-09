import { Injectable, inject, signal } from '@angular/core';
import { ApiClient, AuthService, AuthTokenStore, Role } from 'core';
import {
  ActivityCards,
  AdminEventType,
  AdminNotification,
  LiveStats,
} from './dashboard.model';

/** Connection state of the admin SSE stream. */
export type SseStatus = 'connecting' | 'open' | 'closed';

/** How many notifications to keep in the in-memory feed. */
const MAX_FEED = 50;

/**
 * Proactively recycle the SSE stream with a fresh access token on this cadence,
 * staying ahead of the ~15-min access-token TTL embedded in the stream URL so the
 * connection never dies with a stale token.
 */
const STREAM_RECYCLE_MS = 10 * 60 * 1000;

/** Backoff before reconnecting after a stream error (token refresh happens on the API). */
const RECONNECT_DELAY_MS = 3000;

/**
 * Subscribes to the admin dashboard's Server-Sent Events stream
 * ({@code GET /api/admin/events}) and exposes its events as Angular signals
 * (Req 11.2, 13.3, 17.4, 19.5, 19.6).
 *
 * <p><strong>Auth for EventSource.</strong> The browser {@code EventSource} API
 * cannot set an {@code Authorization} header, so the access token is passed as
 * an {@code ?access_token=} query parameter, which the backend validates like a
 * bearer token; the stream is still ADMIN-only server-side.
 *
 * <p>The service surfaces:
 * <ul>
 *   <li>{@link liveStats} / {@link activity} — the periodic real-time snapshots
 *       pushed by the backend relay (Req 19.5, 19.6);</li>
 *   <li>{@link notifications} — a bounded feed of packed-order, status-change,
 *       claim, and failure alerts (Req 11.2, 13.3, 17.4, 12.4, 14.4);</li>
 *   <li>{@link status} — the connection state, for a status indicator.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class AdminEventsService {
  private readonly api = inject(ApiClient);
  private readonly tokens = inject(AuthTokenStore);
  private readonly auth = inject(AuthService);

  private source: EventSource | null = null;
  /** Pending reconnect timer (token-expiry recovery); null when none scheduled. */
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Whether the caller wants the stream up (so an error-driven reconnect is allowed). */
  private wantConnected = false;
  /** Proactive refresh timer: recycles the stream with a fresh token before expiry. */
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  /** The latest live stats snapshot, or {@code null} until the first push. */
  readonly liveStats = signal<LiveStats | null>(null);

  /** The latest activity-card snapshot, or {@code null} until the first push. */
  readonly activity = signal<ActivityCards | null>(null);

  /** The bounded, newest-first notification feed. */
  readonly notifications = signal<AdminNotification[]>([]);

  /** The current SSE connection state. */
  readonly status = signal<SseStatus>('closed');

  /**
   * Opens the stream (idempotent). No-op when not authenticated or not a staff
   * role. Every staff role connects now: the server scopes delivery per
   * connection, so admins get the operational signals + LIVE_STATS/ACTIVITY while
   * every role gets a lightweight {@code NOTIFICATION} event for the bell items
   * addressed to them (their badge updates live). A storefront CUSTOMER never
   * connects.
   */
  connect(): void {
    if (this.source || typeof EventSource === 'undefined') {
      return;
    }
    if (
      !this.auth.hasAnyRole(
        Role.ADMIN,
        Role.ACCOUNTANT,
        Role.CA,
        Role.SALESPERSON,
        Role.TEAM_LEAD,
        Role.PACKING_USER,
        Role.PAYMENT_VERIFIER,
      )
    ) {
      return;
    }
    this.wantConnected = true;
    this.open();
    // Proactively recycle the stream with a fresh token before the ~15-min access
    // token embedded in the URL expires (the native EventSource would otherwise
    // auto-reconnect with the STALE token and silently stop delivering events).
    // The bell's 60s poll + auth interceptor keep tokens.getAccessToken() fresh.
    if (!this.refreshTimer) {
      this.refreshTimer = setInterval(() => this.recycle(), STREAM_RECYCLE_MS);
    }
  }

  /** (Re)opens the EventSource with the CURRENT token. Internal to connect/reconnect. */
  private open(): void {
    const token = this.tokens.getAccessToken();
    if (!token) {
      this.status.set('closed');
      return;
    }
    const url = `${this.api.url('/api/admin/events')}?access_token=${encodeURIComponent(token)}`;
    this.status.set('connecting');
    const es = new EventSource(url);
    this.source = es;

    es.onopen = () => this.status.set('open');
    es.onerror = () => {
      // A drop is most often the embedded access token having expired. The native
      // EventSource would retry the SAME (stale-token) URL forever, so take over:
      // tear this one down and reconnect with a freshly-read token after a short
      // backoff. Only while the caller still wants the stream up.
      this.status.set('connecting');
      this.scheduleReconnect();
    };

    es.addEventListener('LIVE_STATS', (e) => this.liveStats.set(this.parse<LiveStats>(e)));
    es.addEventListener('ACTIVITY', (e) => this.activity.set(this.parse<ActivityCards>(e)));

    this.listenNotification(es, 'ORDER_AWAITING_APPROVAL');
    this.listenNotification(es, 'ORDER_PACKED');
    this.listenNotification(es, 'ORDER_STATUS_CHANGED');
    this.listenNotification(es, 'CLAIM_FILED_REQUIRED');
    this.listenNotification(es, 'COURIER_ASSIGN_FAILED');
    this.listenNotification(es, 'WHATSAPP_FAILED');
    // Per-recipient bell nudge (all roles). Pushing it into the feed bumps the
    // bell badge live via the bell's existing effect on notifications().
    this.listenNotification(es, 'NOTIFICATION');
  }

  /** Tears down the current stream and reconnects with a fresh token (debounced). */
  private scheduleReconnect(): void {
    if (!this.wantConnected || this.reconnectTimer) {
      return;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.wantConnected) {
        return;
      }
      this.recycle();
    }, RECONNECT_DELAY_MS);
  }

  /** Closes any live stream and immediately re-opens with the current token. */
  private recycle(): void {
    if (!this.wantConnected) {
      return;
    }
    if (this.source) {
      this.source.close();
      this.source = null;
    }
    this.open();
  }

  /** Closes the stream and resets state. */
  disconnect(): void {
    this.wantConnected = false;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
    if (this.source) {
      this.source.close();
      this.source = null;
    }
    this.status.set('closed');
  }

  /** Clears the notification feed (e.g. a "mark all read" action). */
  clearNotifications(): void {
    this.notifications.set([]);
  }

  private listenNotification(es: EventSource, type: AdminEventType): void {
    es.addEventListener(type, (e) => {
      const payload = this.parse<Record<string, unknown>>(e) ?? {};
      this.push(this.toNotification(type, payload));
    });
  }

  private push(notification: AdminNotification): void {
    this.notifications.update((feed) => [notification, ...feed].slice(0, MAX_FEED));
  }

  private toNotification(type: AdminEventType, payload: Record<string, unknown>): AdminNotification {
    const orderCode = (payload['orderCode'] as string | undefined) ?? undefined;
    const orderId = typeof payload['orderId'] === 'number' ? (payload['orderId'] as number) : undefined;
    const codeText = orderCode ? `Order ${orderCode}` : 'An order';
    switch (type) {
      case 'ORDER_AWAITING_APPROVAL': {
        const customer = (payload['customerName'] as string | undefined) ?? '';
        const who = customer ? ` from ${customer}` : '';
        return this.build(
          type,
          'New order needs approval',
          `${codeText}${who} is waiting in the approval queue.`,
          'warning',
          orderCode,
          orderId,
        );
      }
      case 'ORDER_PACKED':
        return this.build(type, 'Order packed', `${codeText} was packed and is ready to ship.`, 'success', orderCode);
      case 'ORDER_STATUS_CHANGED':
        return this.build(
          type,
          'Status updated',
          `${codeText} is now ${this.pretty(payload['newStatus'])}.`,
          'info',
          orderCode,
        );
      case 'CLAIM_FILED_REQUIRED':
        return this.build(
          type,
          'Claim needs filing',
          `File a courier claim for ${codeText}${payload['awb'] ? ` (AWB ${payload['awb']})` : ''}.`,
          'warning',
          orderCode,
        );
      case 'COURIER_ASSIGN_FAILED':
        return this.build(
          type,
          'Courier assignment failed',
          `${codeText} could not get an AWB. ${this.detail(payload['error'])}`,
          'danger',
          orderCode,
        );
      case 'WHATSAPP_FAILED':
        return this.build(
          type,
          'WhatsApp send failed',
          `${codeText} notification failed. ${this.detail(payload['error'])}`,
          'danger',
          orderCode,
        );
      case 'NOTIFICATION': {
        // A per-recipient bell nudge: title/severity come straight from the row.
        const title = (payload['title'] as string | undefined) ?? 'New notification';
        const sev = (payload['severity'] as AdminNotification['severity'] | undefined) ?? 'info';
        return this.build(type, title, '', sev, orderCode);
      }
      default:
        return this.build(type, 'Notification', codeText, 'info', orderCode);
    }
  }

  private build(
    type: AdminEventType,
    title: string,
    detail: string,
    severity: AdminNotification['severity'],
    orderCode?: string,
    orderId?: number,
  ): AdminNotification {
    return { type, title, detail, severity, orderCode, orderId, receivedAt: new Date() };
  }

  private pretty(value: unknown): string {
    return typeof value === 'string' ? value.replace(/_/g, ' ').toLowerCase() : 'updated';
  }

  private detail(value: unknown): string {
    return typeof value === 'string' && value.length > 0 ? value : '';
  }

  private parse<T>(event: MessageEvent): T | null {
    try {
      return JSON.parse(event.data) as T;
    } catch {
      return null;
    }
  }
}
