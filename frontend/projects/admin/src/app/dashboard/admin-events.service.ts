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

  /** The latest live stats snapshot, or {@code null} until the first push. */
  readonly liveStats = signal<LiveStats | null>(null);

  /** The latest activity-card snapshot, or {@code null} until the first push. */
  readonly activity = signal<ActivityCards | null>(null);

  /** The bounded, newest-first notification feed. */
  readonly notifications = signal<AdminNotification[]>([]);

  /** The current SSE connection state. */
  readonly status = signal<SseStatus>('closed');

  /**
   * Opens the stream (idempotent). No-op when not authenticated, or when the
   * user isn't an ADMIN — the {@code /api/admin/events} feed is ADMIN-only
   * server-side, so non-admin roles (salesperson, team lead, accountant, packing)
   * must NOT attempt it (it would 403 the text/event-stream request repeatedly).
   */
  connect(): void {
    if (this.source || typeof EventSource === 'undefined') {
      return;
    }
    if (!this.auth.hasAnyRole(Role.ADMIN)) {
      return;
    }
    const token = this.tokens.getAccessToken();
    if (!token) {
      return;
    }
    const url = `${this.api.url('/api/admin/events')}?access_token=${encodeURIComponent(token)}`;
    this.status.set('connecting');
    const es = new EventSource(url);
    this.source = es;

    es.onopen = () => this.status.set('open');
    es.onerror = () => {
      // EventSource retries automatically; reflect the transient drop in the UI.
      this.status.set(this.source ? 'connecting' : 'closed');
    };

    es.addEventListener('LIVE_STATS', (e) => this.liveStats.set(this.parse<LiveStats>(e)));
    es.addEventListener('ACTIVITY', (e) => this.activity.set(this.parse<ActivityCards>(e)));

    this.listenNotification(es, 'ORDER_PACKED');
    this.listenNotification(es, 'ORDER_STATUS_CHANGED');
    this.listenNotification(es, 'CLAIM_FILED_REQUIRED');
    this.listenNotification(es, 'COURIER_ASSIGN_FAILED');
    this.listenNotification(es, 'WHATSAPP_FAILED');
  }

  /** Closes the stream and resets state. */
  disconnect(): void {
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
    const codeText = orderCode ? `Order ${orderCode}` : 'An order';
    switch (type) {
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
  ): AdminNotification {
    return { type, title, detail, severity, orderCode, receivedAt: new Date() };
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
